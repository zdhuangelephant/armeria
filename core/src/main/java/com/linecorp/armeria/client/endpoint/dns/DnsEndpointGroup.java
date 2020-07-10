/*
 * Copyright 2018 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.client.endpoint.dns;

import static com.google.common.base.Preconditions.checkState;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointGroupException;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.internal.TransportType;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.resolver.dns.DnsNameResolver;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.resolver.dns.DnsServerAddressStreamProvider;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.Promise;
import io.netty.util.concurrent.ScheduledFuture;

/**
 * {@link DynamicEndpointGroup} which resolves targets using DNS queries. This is useful for environments
 * where service discovery is handled using DNS, e.g. Kubernetes uses SkyDNS for service discovery.
 * <br/>
 * 通过DNS查询获取{@link DynamicEndpointGroup}，是在服务发现中会用得到。例如：kubernetes通过SkyDNS来寻找服务主机
 */
abstract class DnsEndpointGroup extends DynamicEndpointGroup {

    // Netty的线程池
    private final EventLoop eventLoop;
    // 最小ttl
    private final int minTtl;
    // 最大ttl
    private final int maxTtl;
    // 重连策略
    private final Backoff backoff;
    //  需要被回答的DnsQuestion集合。这个成员变量是在该类被创建的时候，需要传入
    private final List<DnsQuestion> questions;
    // DNS 解析器【来源于Netty包】
    private final DnsNameResolver resolver;
    private final Logger logger;
    // 私有日志前缀
    private final String logPrefix;

    // 标识是否启动
    private boolean started;
    // 标识是否停止
    private volatile boolean stopped;
    // 盛放调度结果future
    @Nullable
    private volatile ScheduledFuture<?> scheduledFuture;

    // 到现在为止， 重试的次数
    @VisibleForTesting
    int attemptsSoFar;

    DnsEndpointGroup(EventLoop eventLoop, int minTtl, int maxTtl,
                     DnsServerAddressStreamProvider serverAddressStreamProvider,
                     Backoff backoff, Iterable<DnsQuestion> questions,
                     Consumer<DnsNameResolverBuilder> resolverConfigurator) {

        this.eventLoop = eventLoop;
        this.minTtl = minTtl;
        this.maxTtl = maxTtl;
        this.backoff = backoff;
        this.questions = ImmutableList.copyOf(questions);
        assert !this.questions.isEmpty();
        logger = LoggerFactory.getLogger(getClass());
        // 对已经存在的DnsQuestion集合进行去重处理
        logPrefix = this.questions.stream()
                                  .map(DnsQuestion::name)
                                  .distinct()
                                  .collect(Collectors.joining(", ", "[", "]"));

        final DnsNameResolverBuilder resolverBuilder = new DnsNameResolverBuilder(eventLoop)
                .channelType(TransportType.datagramChannelType(eventLoop.parent()))
                .ttl(minTtl, maxTtl)
                .traceEnabled(true)
                .nameServerProvider(serverAddressStreamProvider);

        resolverConfigurator.accept(resolverBuilder);
        resolver = resolverBuilder.build();
    }

    final Logger logger() {
        return logger;
    }

    final String logPrefix() {
        return logPrefix;
    }

    /**
     * Invoke this method at the end of the subclass constructor to initiate the queries.
     * 在子类构造器的最后，调用此方法。
     */
    final void start() {
        checkState(!started);
        started = true;
        eventLoop.execute(this::sendQueries);
    }


    private void sendQueries() {
        if (stopped) {
            return;
        }

        final Future<List<DnsRecord>> future;
        final int numQuestions = questions.size();
        if (numQuestions == 1) {
            // Simple case of single query
            final DnsQuestion question = questions.get(0);
            logger.debug("{} Sending a DNS query", logPrefix);
            future = resolver.resolveAll(question);
        } else {
            // Multiple queries
            logger.debug("{} Sending DNS queries", logPrefix);
            // 下文中， 会把 aggregatedPromise 赋值给上面的future变量
            final Promise<List<DnsRecord>> aggregatedPromise = eventLoop.newPromise();
            final FutureListener<List<DnsRecord>> listener = new FutureListener<List<DnsRecord>>() {
                private final List<DnsRecord> records = new ArrayList<>();
                private int remaining = numQuestions;
                @Nullable
                private List<Throwable> causes;

                @Override
                public void operationComplete(Future<List<DnsRecord>> future) throws Exception {
                    if (future.isSuccess()) {
                        final List<DnsRecord> records = future.getNow();
                        this.records.addAll(records);
                    } else {
                        if (causes == null) {
                            causes = new ArrayList<>(numQuestions);
                        }
                        causes.add(future.cause());
                    }
                    //当 remaining的个数被处理完毕的时候， 这个records就立马被设置进aggregatedPromise。如此刁钻。如此不要脸。如此狼狈。
                    if (--remaining == 0) {
                        if (!records.isEmpty()) {
                            aggregatedPromise.setSuccess(records);
                        } else {
                            final Throwable aggregatedCause;
                            if (causes == null) {
                                aggregatedCause =
                                        new EndpointGroupException("empty result returned by DNS server");
                            } else {
                                aggregatedCause = new EndpointGroupException("failed to receive DNS records");
                                for (Throwable c : causes) {
                                    aggregatedCause.addSuppressed(c);
                                }
                            }
                            aggregatedPromise.setFailure(aggregatedCause);
                        }
                    }
                }
            };

            // Future类型可以添加监听器listener
            questions.forEach(q -> resolver.resolveAll(q).addListener(listener));
            future = aggregatedPromise;
        }

        attemptsSoFar++;
        future.addListener(this::onDnsRecords);
    }

    /**
     * 将 DnsRecord转换为 scheduledFuture
     * @param future
     */
    private void onDnsRecords(Future<? super List<DnsRecord>> future) {
        if (stopped) {
            if (future.isSuccess()) {
                @SuppressWarnings("unchecked")
                final List<DnsRecord> result = (List<DnsRecord>) future.getNow();
                result.forEach(ReferenceCountUtil::safeRelease);
            }
            return;
        }

        if (!future.isSuccess()) {
            // Failed. Try again with the delay given by Backoff.
            final long delayMillis = backoff.nextDelayMillis(attemptsSoFar);

            logger.warn("{} DNS query failed; retrying in {} ms (attempts so far: {}):",
                        logPrefix, delayMillis, attemptsSoFar, future.cause());
            scheduledFuture = eventLoop.schedule(this::sendQueries, delayMillis, TimeUnit.MILLISECONDS);
            return;
        }

        // Reset the counter so that Backoff is reset.
        attemptsSoFar = 0;

        @SuppressWarnings("unchecked")
        final List<DnsRecord> records = (List<DnsRecord>) future.getNow();
        final long serverTtl = records.stream().mapToLong(DnsRecord::timeToLive).min().orElse(minTtl);
        final int effectiveTtl = (int) Math.max(Math.min(serverTtl, maxTtl), minTtl);

        try {
            setEndpoints(onDnsRecords(records, effectiveTtl));
        } catch (Throwable t) {
            logger.warn("{} Failed to process the DNS query result: {}", logPrefix, records, t);
        } finally {
            records.forEach(ReferenceCountUtil::safeRelease);
            scheduledFuture = eventLoop.schedule(this::sendQueries, effectiveTtl, TimeUnit.SECONDS);
        }
    }

    /**
     * Invoked when DNS records were retrieved from a DNS server. Implement this method to transform
     * {@link DnsRecord}s into {@link Endpoint}s.
     * <p/>
     * 当从DNS服务器上取回一条DNS记录的时候此方法会被调用。实现此方法的目的是把DnsRecord转变成Endpoint。
     */
    abstract ImmutableSortedSet<Endpoint> onDnsRecords(List<DnsRecord> records, int ttl) throws Exception;

    /**
     * Stops polling DNS servers for service updates.
     * <p/>
     * 停止对DNS服务器的沦胥操作
     */
    @Override
    public final void close() {
        stopped = true;
        super.close();
        final ScheduledFuture<?> scheduledFuture = this.scheduledFuture;
        if (scheduledFuture != null) {
            scheduledFuture.cancel(true);
        }
    }

    /**
     * Logs a warning message about an invalid record.
     * 对违法记录输出警告的日志。
     */
    final void warnInvalidRecord(DnsRecordType type, ByteBuf content) {
        if (logger().isWarnEnabled()) {
            final String dump = ByteBufUtil.hexDump(content);
            logger().warn("{} Skipping invalid {} record: {}",
                          logPrefix(), type.name(), dump.isEmpty() ? "<empty>" : dump);
        }
    }
}
