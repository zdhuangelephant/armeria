/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.client.limit;

import static java.util.Objects.requireNonNull;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.SimpleDecoratingClient;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.RequestTimeoutException;

import io.netty.util.concurrent.ScheduledFuture;

/**
 * An abstract {@link Client} decorator that limits the concurrent number of active requests.
 * <br/>
 * 限制当前活跃链接数的Client抽象类
 *
 * <p>{@link #numActiveRequests()} increases when {@link Client#execute(ClientRequestContext, Request)} is
 * invoked and decreases when the {@link Response} returned by the
 * {@link Client#execute(ClientRequestContext, Request)} is closed. When {@link #numActiveRequests()} reaches
 * at the configured {@code maxConcurrency} the {@link Request}s are deferred until the currently active
 * {@link Request}s are completed.
 * <br/>
 * 当{@link Client#execute(ClientRequestContext, Request)} 被调用的时候，{@link #numActiveRequests()}会增加;
 * 当{@link Client#execute(ClientRequestContext, Request)} 返回{@link Response}的时候， {@link #numActiveRequests()}会减少;
 * 当{@link #numActiveRequests()}达到{@link Request}所配置的{@code maxConcurrency}的时候，如果再有请求进来则会被延迟，一直等到前面任何一个请求响应后，腾出一个空来
 *
 * @param <I> the {@link Request} type
 * @param <O> the {@link Response} type
 */
public abstract class ConcurrencyLimitingClient<I extends Request, O extends Response>
        extends SimpleDecoratingClient<I, O> {

    // 默认的超时时间 10s
    private static final long DEFAULT_TIMEOUT_MILLIS = 10000L;

    // 最大并发数阈值 其值等于0的话，表示禁用限流
    private final int maxConcurrency;
    // 和上面的区别: 当前req未被传递给{@code delegate}时，需要等待的时间，到了这个时间后依然没有传递给delegate时，则当前client会自动fail当前req。
    private final long timeoutMillis;
    // 当前正在活跃的请求个数
    private final AtomicInteger numActiveRequests = new AtomicInteger();
    // 如果是限流策略，则将每个请求被封装成task将会存入queue; 如果未采取限流策略，则就不需要将每个request放入queue
    private final Queue<PendingTask> pendingRequests = new ConcurrentLinkedQueue<>();

    /**
     * Creates a new instance that decorates the specified {@code delegate} to limit the concurrent number of
     * active requests to {@code maxConcurrency}, with the default timeout of {@value #DEFAULT_TIMEOUT_MILLIS}
     * milliseconds.
     *
     * @param delegate the delegate {@link Client}
     * @param maxConcurrency the maximum number of concurrent active requests. {@code 0} to disable the limit.
     */
    protected ConcurrencyLimitingClient(Client<I, O> delegate, int maxConcurrency) {
        this(delegate, maxConcurrency, DEFAULT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    }

    /**
     * Creates a new instance that decorates the specified {@code delegate} to limit the concurrent number of
     * active requests to {@code maxConcurrency}.
     *
     * @param delegate the delegate {@link Client}
     * @param maxConcurrency the maximum number of concurrent active requests. {@code 0} to disable the limit.
     * @param timeout the amount of time until this decorator fails the request if the request was not
     *                delegated to the {@code delegate} before then. 当前req未被传递给{@code delegate}时，需要等待的时间，到了这个时间后依然没有传递给delegate时，则当前client会自动fail当前req。
     */
    protected ConcurrencyLimitingClient(Client<I, O> delegate,
                                        int maxConcurrency, long timeout, TimeUnit unit) {
        super(delegate);

        validateAll(maxConcurrency, timeout, unit);

        this.maxConcurrency = maxConcurrency;
        timeoutMillis = unit.toMillis(timeout);
    }

    /**
     *  校验maxConcurrency > 0;
     *  校验timeout > 0;
     *  校验时间单位不允许为空;
     * @param maxConcurrency
     * @param timeout
     * @param unit
     */
    static void validateAll(int maxConcurrency, long timeout, TimeUnit unit) {
        validateMaxConcurrency(maxConcurrency);
        if (timeout < 0) {
            throw new IllegalArgumentException("timeout: " + timeout + " (expected: >= 0)");
        }
        requireNonNull(unit, "unit");
    }

    static void validateMaxConcurrency(int maxConcurrency) {
        if (maxConcurrency < 0) {
            throw new IllegalArgumentException("maxConcurrency: " + maxConcurrency + " (expected: >= 0)");
        }
    }

    /**
     * Returns the number of the {@link Request}s that are being executed.
     * 返回正在被处理的请求个数
     */
    public int numActiveRequests() {
        return numActiveRequests.get();
    }

    @Override
    public O execute(ClientRequestContext ctx, I req) throws Exception {
        // 首先判断， 是否开启并发限制。如果maxConcurrency=0， 表示禁用限流
        return maxConcurrency == 0 ? unlimitedExecute(ctx, req)
                                   : limitedExecute(ctx, req);
    }

    /**
     * 有限制的访问执行逻辑
     * @param ctx
     * @param req
     * @return
     * @throws Exception
     */
    private O limitedExecute(ClientRequestContext ctx, I req) throws Exception {

        final Deferred<O> deferred = defer(ctx, req);
        final PendingTask currentTask = new PendingTask(ctx, req, deferred);
        // 只有在限流的策略下，queue才会被用到
        pendingRequests.add(currentTask);
        drain();

        if (!currentTask.isRun() && timeoutMillis != 0) {
            // Current request was not delegated. Schedule a timeout.
            // 可能当前的request未来得及执行[队列内积压了过多的请求]，所以需要来个因超时中断此请求的定时任务。
            final ScheduledFuture<?> timeoutFuture = ctx.eventLoop().schedule(
                    () -> deferred
                            .close(new UnprocessedRequestException(RequestTimeoutException.get())),
                    timeoutMillis, TimeUnit.MILLISECONDS);
            currentTask.set(timeoutFuture);
        }

        return deferred.response();
    }

    /**
     * 无限制的访问执行逻辑
     * @param ctx
     * @param req
     * @return
     * @throws Exception
     */
    private O unlimitedExecute(ClientRequestContext ctx, I req) throws Exception {
        numActiveRequests.incrementAndGet();
        boolean success = false;
        try {
            final O res = delegate().execute(ctx, req);
            res.completionFuture().handle((unused, cause) -> {
                numActiveRequests.decrementAndGet();
                return null;
            });
            success = true;
            return res;
        } finally {
            if (!success) {
                numActiveRequests.decrementAndGet();
            }
        }
    }

    /**
     * 此方法会被在两个地方调用的到
     * 1、当请求进入后，先封装请求为PendingTask对象并扔进pendingRequests队列后，第一次调用drain()。
     * 2、在每一个PendingTask的run方法内部，第二次, 第三次, 第四次, 第n次调用 ....调用。并伴随着第一次、二次、三次、四次、n次入栈和出栈。
     *
     * notes:
     *  至此，终于明白了为啥会有个栈溢出的控制逻辑！
     */
    void drain() {
        while (!pendingRequests.isEmpty()) {
            // 先进行阈值判断，查看是否超过最大maxConcurrency， 如果达到上限，则直接中断
            final int currentActiveRequests = numActiveRequests.get();
            if (currentActiveRequests >= maxConcurrency) {
                break;
            }
            // 通过CAS进行叠加
            if (numActiveRequests.compareAndSet(currentActiveRequests, currentActiveRequests + 1)) {
                final PendingTask task = pendingRequests.poll();
                if (task == null) {
                    numActiveRequests.decrementAndGet();
                    if (!pendingRequests.isEmpty()) {
                        // Another request might have been added to the queue while numActiveRequests reached
                        // at its limit.
                        // 当从pendingRequests队列内poll出任务的手， 可能在其处理的短暂时间内，有任务被放入pendingRequests队列内了，
                        // 所以如果真是如此， 则应该继续循环，直到pendingRequests内没有了任务为止。
                        continue;
                    } else {
                        break;
                    }
                }

                // 如果任务不为空， 则就让其run起来
                task.run();
            }
        }
    }

    /**
     * Defers the specified {@link Request}.
     * <br/>
     * 返回一个Deferred对象，其作用是延迟更新req执行的结果集
     *
     * @return a new {@link Deferred} which provides the interface for updating the result of
     *         {@link Request} execution later.
     */
    protected abstract Deferred<O> defer(ClientRequestContext ctx, I req) throws Exception;

    /**
     * Provides the interface for updating the result of a {@link Request} execution when its {@link Response}
     * is ready.
     * <br/>
     * 当Response准备就绪时，提供了一个可以修改Request执行结果集的接口 的类。
     *
     * @param <O> the {@link Response} type
     */
    public interface Deferred<O extends Response> {
        /**
         * Returns the {@link Response} which will delegate to the {@link Response} set by
         * {@link #delegate(Response)}.
         * <br/>
         * 返回{@link #delegate(Response)}方法的参数即Response对象， 是的，该方法的范返回值就是{@link #delegate(Response)}中的参数。
         */
        O response();

        /**
         * Delegates the {@link #response() response} to the specified {@link Response}.
         * <br/>
         * 分配{@link #response() response}方法的返回值，给指定的{@link Response}
         */
        void delegate(O response);

        /**
         * Closes the {@link #response()} without delegating. 不分配给delegator的情况下，关闭{@link #response() response}
         */
        void close(Throwable cause);
    }

    /**
     *  是Runable接口实现类的同时，又是ScheduledFuture泛型的继承类
     */
    private final class PendingTask extends AtomicReference<ScheduledFuture<?>> implements Runnable {

        private static final long serialVersionUID = -7092037489640350376L;

        private final ClientRequestContext ctx;
        private final I req;
        private final Deferred<O> deferred;
        private boolean isRun;

        PendingTask(ClientRequestContext ctx, I req, Deferred<O> deferred) {
            this.ctx = ctx;
            this.req = req;
            this.deferred = deferred;
        }

        boolean isRun() {
            return isRun;
        }

        @Override
        public void run() {
            isRun = true;

            /**
             * 这个地方之所有会先取出timeoutFuture，是因为在{@link #limitedExecute(ClientRequestContext, Request)}  方法内部，设置了timeoutFuture。
             */
            final ScheduledFuture<?> timeoutFuture = get();
            if (timeoutFuture != null) {
                // 当执行到这的时候，当前的task可能已经被执行完毕，也可能因为前面积压的task过多，导致此task超时而被中断。
                if (timeoutFuture.isDone() || !timeoutFuture.cancel(false)) {
                    // Timeout task ran already or is determined to run.
                    // 如果任务是已经完成或者被中断，则将活跃请求数减一
                    numActiveRequests.decrementAndGet();
                    return;
                }
            }

            /**
             * 在try(xxxx x = xxx())内的代码会自动释放资源。前提是一定要实现java.lang.AutoCloseable的接口。
             *
             * eg jdk的IO流，或网络资源的连接都可以卸载括号内。就不需要在显示的调用close。
             */
            try (SafeCloseable ignored = ctx.push()) {
                try {
                    // 获取的真正结果
                    final O actualRes = delegate().execute(ctx, req);
                    actualRes.completionFuture().handleAsync((unused, cause) -> {
                        numActiveRequests.decrementAndGet();
                        // 继续唤醒下一个任务
                        drain();
                        return null;
                    }, ctx.eventLoop());
                    // 设置实际的响应给deferred
                    deferred.delegate(actualRes);
                } catch (Throwable t) {
                    numActiveRequests.decrementAndGet();
                    deferred.close(t);
                }
            }
        }
    }
}
