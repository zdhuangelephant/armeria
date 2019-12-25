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
package com.linecorp.armeria.client;

import java.lang.reflect.Array;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.SessionProtocol;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.util.NetUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;

/**
 * Http连接池定义。
 * 这个类才是真正的连接池。类似于数据库连接池一样。
 */
final class HttpChannelPool implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(HttpChannelPool.class);

    // 这个八成是NioEventLoop
    private final EventLoop eventLoop;

    // 标记当前Pool是否已经关闭
    private boolean closed;

    // 放置连接的大池子
    // Fields for pooling connections:
    private final Map<PoolKey, Deque<PooledChannel>>[] pool;
    private final Map<PoolKey, CompletableFuture<PooledChannel>>[] pendingAcquisitions;
    // 所有channel的声明
    private final Map<Channel, Boolean> allChannels;
    // 连接的监听器
    private final ConnectionPoolListener listener;

    // 创建新的conn会用到的字段
    // Fields for creating a new connection:
    private final Bootstrap[] bootstraps;
    // 连接超时时间
    private final int connectTimeoutMillis;

    HttpChannelPool(HttpClientFactory clientFactory, EventLoop eventLoop, ConnectionPoolListener listener) {
        this.eventLoop = eventLoop;

        pool = newEnumMap(
                Map.class,
                unused -> new HashMap<>(),
                SessionProtocol.H1, SessionProtocol.H1C,
                SessionProtocol.H2, SessionProtocol.H2C);
        pendingAcquisitions = newEnumMap(
                Map.class,
                unused -> new HashMap<>(),
                SessionProtocol.HTTP, SessionProtocol.HTTPS,
                SessionProtocol.H1, SessionProtocol.H1C,
                SessionProtocol.H2, SessionProtocol.H2C);
        allChannels = new IdentityHashMap<>();
        this.listener = listener;

        final Bootstrap baseBootstrap = clientFactory.newBootstrap();
        baseBootstrap.group(eventLoop);
        bootstraps = newEnumMap(
                Bootstrap.class,
                desiredProtocol -> {
                    final Bootstrap bootstrap = baseBootstrap.clone();
                    bootstrap.handler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) throws Exception {
                            ch.pipeline().addLast(
                                    new HttpClientPipelineConfigurator(clientFactory, desiredProtocol));
                        }
                    });
                    return bootstrap;
                },
                SessionProtocol.HTTP, SessionProtocol.HTTPS,
                SessionProtocol.H1, SessionProtocol.H1C,
                SessionProtocol.H2, SessionProtocol.H2C);
        connectTimeoutMillis = (Integer) baseBootstrap.config().options()
                                                      .get(ChannelOption.CONNECT_TIMEOUT_MILLIS);
    }

    /**
     * Returns an array whose index signifies {@link SessionProtocol#ordinal()}. Similar to {@link EnumMap}.
     */
    private static <T> T[] newEnumMap(Class<?> elementType,
                                      Function<SessionProtocol, T> factory,
                                      SessionProtocol... allowedProtocols) {
        @SuppressWarnings("unchecked")
        final T[] maps = (T[]) Array.newInstance(elementType, SessionProtocol.values().length);
        // Attempting to access the array with an unallowed protocol will trigger NPE,
        // which will help us find a bug.
        for (SessionProtocol p : allowedProtocols) {
            maps[p.ordinal()] = factory.apply(p);
        }
        return maps;
    }

    private Bootstrap getBootstrap(SessionProtocol desiredProtocol) {
        return bootstraps[desiredProtocol.ordinal()];
    }

    @Nullable
    private Deque<PooledChannel> getPool(SessionProtocol protocol, PoolKey key) {
        return pool[protocol.ordinal()].get(key);
    }

    private Deque<PooledChannel> getOrCreatePool(SessionProtocol protocol, PoolKey key) {
        return pool[protocol.ordinal()].computeIfAbsent(key, k -> new ArrayDeque<>());
    }

    @Nullable
    private CompletableFuture<PooledChannel> getPendingAcquisition(SessionProtocol desiredProtocol,
                                                                   PoolKey key) {
        return pendingAcquisitions[desiredProtocol.ordinal()].get(key);
    }

    private void setPendingAcquisition(SessionProtocol desiredProtocol, PoolKey key,
                                       CompletableFuture<PooledChannel> future) {
        pendingAcquisitions[desiredProtocol.ordinal()].put(key, future);
    }

    private void removePendingAcquisition(SessionProtocol desiredProtocol, PoolKey key) {
        pendingAcquisitions[desiredProtocol.ordinal()].remove(key);
    }

    /**
     * Attempts to acquire a {@link Channel} which is matched by the specified condition immediately.
     * <br/>
     * 立即获取参数条件中匹配到的{@link Channel}
     *
     * @return {@code null} is there's no match left in the pool and thus a new connection has to be
     *         requested via {@link #acquireLater(SessionProtocol, PoolKey, ClientConnectionTimingsBuilder)}.
     *         如果在池子内没有匹配的Channel，则需要调用{@link #acquireLater(SessionProtocol, PoolKey, ClientConnectionTimingsBuilder)}
     */
    @Nullable
    PooledChannel acquireNow(SessionProtocol desiredProtocol, PoolKey key) {
        PooledChannel ch;
        switch (desiredProtocol) {
            case HTTP:
                ch = acquireNowExact(key, SessionProtocol.H2C);
                if (ch == null) {
                    ch = acquireNowExact(key, SessionProtocol.H1C);
                }
                break;
            case HTTPS:
                ch = acquireNowExact(key, SessionProtocol.H2);
                if (ch == null) {
                    ch = acquireNowExact(key, SessionProtocol.H1);
                }
                break;
            default:
                ch = acquireNowExact(key, desiredProtocol);
        }
        return ch;
    }

    @Nullable
    private PooledChannel acquireNowExact(PoolKey key, SessionProtocol protocol) {
        final Deque<PooledChannel> queue = getPool(protocol, key);
        if (queue == null) {
            return null;
        }

        // Find the most recently released channel while cleaning up the unhealthy channels.
        for (int i = queue.size(); i > 0; i--) {
            final PooledChannel pooledChannel = queue.peekLast();
            if (!isHealthy(pooledChannel)) {
                queue.removeLast();
                continue;
            }

            final HttpSession session = HttpSession.get(pooledChannel.get());
            if (session.unfinishedResponses() >= session.maxUnfinishedResponses()) {
                // The channel is full of streams so we cannot create a new one.
                // Move the channel to the beginning of the queue so it has low priority.
                // 这个channel里面依然有流，所以我们不能创建一个新的。然后将这个含流的channel重新放入队列的开始的位置，以至于会有更低的优先级。
                queue.removeLast();
                queue.addFirst(pooledChannel);
                continue;
            }

            if (!protocol.isMultiplex()) {
                queue.removeLast();
            }
            return pooledChannel;
        }

        return null;
    }

    private static boolean isHealthy(PooledChannel pooledChannel) {
        // 拿到当前hannel
        final Channel ch = pooledChannel.get();
        // channel是否活跃 &&
        return ch.isActive() && HttpSession.get(ch).canSendRequest();
    }

    @Nullable
    private static SessionProtocol getProtocolIfHealthy(Channel ch) {
        if (!ch.isActive()) {
            return null;
        }

        // Note that we do not need to check 'HttpSession.isActive()'
        // because an inactive session always returns null.
        return HttpSession.get(ch).protocol();
    }

    /**
     * Acquires a new {@link Channel} which is matched by the specified condition by making a connection
     * attempt or waiting for the current connection attempt in progress.
     * <br/>
     * 获取一个新的Channel，通过创建一个conn或者等待正在处理着的当前conn。
     */
    CompletableFuture<PooledChannel> acquireLater(SessionProtocol desiredProtocol, PoolKey key,
                                                  ClientConnectionTimingsBuilder timingsBuilder) {
        final CompletableFuture<PooledChannel> promise = new CompletableFuture<>();
        if (!usePendingAcquisition(desiredProtocol, key, promise, timingsBuilder)) {
            connect(desiredProtocol, key, promise, timingsBuilder);
        }
        return promise;
    }

    /**
     * Tries to use the pending HTTP/2 connection to avoid creating an extra connection.
     * 尝试用挂起的 HTTP/2 连接来避免创建额外的连接。
     *
     * @return {@code true} if succeeded to reuse the pending connection. 如果重用等待的连接成功，则返回true
     */
    private boolean usePendingAcquisition(SessionProtocol desiredProtocol, PoolKey key,
                                          CompletableFuture<PooledChannel> promise,
                                          ClientConnectionTimingsBuilder timingsBuilder) {

        // 判断是否是 HTTP/1
        if (desiredProtocol == SessionProtocol.H1 || desiredProtocol == SessionProtocol.H1C) {
            // Can't use HTTP/1 connections because they will not be available in the pool until
            // the request is done.
            // 不能使用HTTP/1，因为他们在请求完成之前一直是不可用的状态。
            return false;
        }

        final CompletableFuture<PooledChannel> pendingAcquisition =
                getPendingAcquisition(desiredProtocol, key);

        if (pendingAcquisition == null) {
            return false;
        }

        timingsBuilder.pendingAcquisitionStart();
        pendingAcquisition.handle((pch, cause) -> {
            timingsBuilder.pendingAcquisitionEnd();

            if (cause == null) {
                final SessionProtocol actualProtocol = pch.protocol();
                if (actualProtocol.isMultiplex()) {
                    promise.complete(pch);
                } else {
                    // Try to acquire again because the connection was not HTTP/2.
                    // We use the exact protocol (H1 or H1C) instead of 'desiredProtocol' so that
                    // we do not waste our time looking for pending acquisitions for the host
                    // that does not support HTTP/2.
                    final PooledChannel ch = acquireNow(actualProtocol, key);
                    if (ch != null) {
                        promise.complete(ch);
                    } else {
                        connect(actualProtocol, key, promise, timingsBuilder);
                    }
                }
            } else {
                // The pending connection attempt has failed.
                connect(desiredProtocol, key, promise, timingsBuilder);
            }
            return null;
        });

        return true;
    }

    private void connect(SessionProtocol desiredProtocol, PoolKey key, CompletableFuture<PooledChannel> promise,
                         ClientConnectionTimingsBuilder timingsBuilder) {

        setPendingAcquisition(desiredProtocol, key, promise);
        timingsBuilder.socketConnectStart();

        final InetSocketAddress remoteAddress;
        try {
            remoteAddress = toRemoteAddress(key);
        } catch (UnknownHostException e) {
            notifyConnect(desiredProtocol, key, eventLoop.newFailedFuture(e), promise, timingsBuilder);
            return;
        }

        // Fail immediately if it is sure that the remote address doesn't support the desired protocol.
        if (SessionProtocolNegotiationCache.isUnsupported(remoteAddress, desiredProtocol)) {
            notifyConnect(desiredProtocol, key,
                          eventLoop.newFailedFuture(
                                  new SessionProtocolNegotiationException(
                                          desiredProtocol, "previously failed negotiation")),
                          promise, timingsBuilder);
            return;
        }

        // 创建一个新的链接
        // Create a new connection.
        final Promise<Channel> sessionPromise = eventLoop.newPromise();
        connect(remoteAddress, desiredProtocol, sessionPromise);

        if (sessionPromise.isDone()) {
            notifyConnect(desiredProtocol, key, sessionPromise, promise, timingsBuilder);
        } else {
            sessionPromise.addListener((Future<Channel> future) -> {
                notifyConnect(desiredProtocol, key, future, promise, timingsBuilder);
            });
        }
    }

    /**
     * A low-level operation that triggers a new connection attempt. Used only by:
     * <ul>
     *   <li>{@link #connect(SessionProtocol, PoolKey, CompletableFuture, ClientConnectionTimingsBuilder)} -
     *       The pool has been exhausted.</li>
     *   <li>{@link HttpSessionHandler} - HTTP/2 upgrade has failed.</li>
     * </ul>
     * <br/>
     * 一个低级别触发一个新连接的进行尝试的操作。仅仅在以下情况中调用此方法
     * <ul>
     *     <li>{@link #connect(SessionProtocol, PoolKey, CompletableFuture, ClientConnectionTimingsBuilder)} - 连接池已经疲劳了的情况下</li>
     *     <li>{@link HttpSessionHandler} - Http/2 更新失败的情况下</li>
     * </ul>
     */
    void connect(SocketAddress remoteAddress, SessionProtocol desiredProtocol,
                 Promise<Channel> sessionPromise) {
        final Bootstrap bootstrap = getBootstrap(desiredProtocol);
        // 向远端发起建立连接。
        final ChannelFuture connectFuture = bootstrap.connect(remoteAddress);

        connectFuture.addListener((ChannelFuture future) -> {
            if (future.isSuccess()) {
                initSession(desiredProtocol, future, sessionPromise);
            } else {
                sessionPromise.setFailure(future.cause());
            }
        });
    }

    private static InetSocketAddress toRemoteAddress(PoolKey key) throws UnknownHostException {
        final InetAddress inetAddr = InetAddress.getByAddress(
                key.host, NetUtil.createByteArrayFromIpAddressString(key.ipAddr));
        return new InetSocketAddress(inetAddr, key.port);
    }

    /**
     * 初始化会话
     * @param desiredProtocol  协议 eg: http/https/...
     * @param connectFuture   连接是否成功的future
     * @param sessionPromise
     */
    private void initSession(SessionProtocol desiredProtocol, ChannelFuture connectFuture,
                             Promise<Channel> sessionPromise) {
        // 断言，只有connectFuture是成功的情况下initSession(xxx)才会被调用。
        assert connectFuture.isSuccess();

        final Channel ch = connectFuture.channel();
        final EventLoop eventLoop = ch.eventLoop();
        assert eventLoop.inEventLoop();

        final ScheduledFuture<?> timeoutFuture = eventLoop.schedule(() -> {
            // 将其标记为失败， 并且唤醒与之绑定的所有监听者
            if (sessionPromise.tryFailure(new SessionProtocolNegotiationException(
                    desiredProtocol, "connection established, but session creation timed out: " + ch))) {
                ch.close();
            }
        }, connectTimeoutMillis, TimeUnit.MILLISECONDS);

        ch.pipeline().addLast(new HttpSessionHandler(this, ch, sessionPromise, timeoutFuture));
    }

    private void notifyConnect(SessionProtocol desiredProtocol, PoolKey key, Future<Channel> future,
                               CompletableFuture<PooledChannel> promise,
                               ClientConnectionTimingsBuilder timingsBuilder) {
        assert future.isDone();
        removePendingAcquisition(desiredProtocol, key);

        timingsBuilder.socketConnectEnd();
        try {
            if (future.isSuccess()) {
                final Channel channel = future.getNow();
                final SessionProtocol protocol = getProtocolIfHealthy(channel);
                if (closed || protocol == null) {
                    channel.close();
                    promise.completeExceptionally(
                            new UnprocessedRequestException(ClosedSessionException.get()));
                    return;
                }

                allChannels.put(channel, Boolean.TRUE);

                try {
                    listener.connectionOpen(protocol,
                                            (InetSocketAddress) channel.remoteAddress(),
                                            (InetSocketAddress) channel.localAddress(),
                                            channel);
                } catch (Exception e) {
                    if (logger.isWarnEnabled()) {
                        logger.warn("{} Exception handling {}.connectionOpen()",
                                    channel, listener.getClass().getName(), e);
                    }
                }

                final HttpSession session = HttpSession.get(channel);
                if (session.unfinishedResponses() < session.maxUnfinishedResponses()) {
                    if (protocol.isMultiplex()) {
                        final Http2PooledChannel pooledChannel = new Http2PooledChannel(channel, protocol);
                        addToPool(protocol, key, pooledChannel);
                        promise.complete(pooledChannel);
                    } else {
                        promise.complete(new Http1PooledChannel(channel, protocol, key));
                    }
                } else {
                    // Server set MAX_CONCURRENT_STREAMS to 0, which means we can't send anything.
                    channel.close();
                    promise.completeExceptionally(
                            new UnprocessedRequestException(RefusedStreamException.get()));
                }

                channel.closeFuture().addListener(f -> {
                    allChannels.remove(channel);

                    // Clean up old unhealthy channels by iterating from the beginning of the queue.
                    final Deque<PooledChannel> queue = getPool(protocol, key);
                    if (queue != null) {
                        for (;;) {
                            final PooledChannel pooledChannel = queue.peekFirst();
                            if (pooledChannel == null || isHealthy(pooledChannel)) {
                                break;
                            }
                            queue.removeFirst();
                        }
                    }

                    try {
                        listener.connectionClosed(protocol,
                                                  (InetSocketAddress) channel.remoteAddress(),
                                                  (InetSocketAddress) channel.localAddress(),
                                                  channel);
                    } catch (Exception e) {
                        if (logger.isWarnEnabled()) {
                            logger.warn("{} Exception handling {}.connectionClosed()",
                                        channel, listener.getClass().getName(), e);
                        }
                    }
                });
            } else {
                promise.completeExceptionally(new UnprocessedRequestException(future.cause()));
            }
        } catch (Exception e) {
            promise.completeExceptionally(new UnprocessedRequestException(e));
        }
    }

    /**
     * Adds a {@link Channel} to this pool.
     * 添加一个Channel到大池子
     */
    private void addToPool(SessionProtocol actualProtocol, PoolKey key, PooledChannel pooledChannel) {
        assert eventLoop.inEventLoop() : Thread.currentThread().getName();
        getOrCreatePool(actualProtocol, key).addLast(pooledChannel);
    }

    /**
     * Closes all {@link Channel}s managed by this pool.
     * 关闭被这个池子管理的所有Channel
     */
    @Override
    public void close() {
        closed = true;

        if (eventLoop.inEventLoop()) {
            // While we'd prefer to block until the pool is actually closed, we cannot block for the channels to
            // close if it was called from the event loop or we would deadlock. In practice, it's rare to call
            // close from an event loop thread, and not a main thread.
            doCloseAsync();
        } else {
            doCloseSync();
        }
    }

    private void doCloseAsync() {
        if (allChannels.isEmpty()) {
            return;
        }

        final List<ChannelFuture> closeFutures = new ArrayList<>(allChannels.size());
        for (Channel ch : allChannels.keySet()) {
            // NB: Do not call close() here, because it will trigger the closeFuture listener
            //     which mutates allChannels.
            closeFutures.add(ch.closeFuture());
        }

        closeFutures.forEach(f -> f.channel().close());
    }

    private void doCloseSync() {
        final CountDownLatch outerLatch = eventLoop.submit(() -> {
            if (allChannels.isEmpty()) {
                return null;
            }

            final int numChannels = allChannels.size();
            final CountDownLatch latch = new CountDownLatch(numChannels);
            final List<ChannelFuture> closeFutures = new ArrayList<>(numChannels);
            for (Channel ch : allChannels.keySet()) {
                // NB: Do not call close() here, because it will trigger the closeFuture listener
                //     which mutates allChannels.
                final ChannelFuture f = ch.closeFuture();
                closeFutures.add(f);
                f.addListener((ChannelFutureListener) future -> latch.countDown());
            }
            closeFutures.forEach(f -> f.channel().close());
            return latch;
        }).syncUninterruptibly().getNow();

        if (outerLatch != null) {
            boolean interrupted = false;
            while (outerLatch.getCount() != 0) {
                try {
                    outerLatch.await();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 被池化的时候， 需要的key，在大池子内{@link #pool}
     */
    static final class PoolKey {
        // 主机
        final String host;
        // ip地址
        final String ipAddr;
        // 端口
        final int port;
        // hashcode
        final int hashCode;

        PoolKey(String host, String ipAddr, int port) {
            this.host = host;
            this.ipAddr = ipAddr;
            this.port = port;
            hashCode = (host.hashCode() * 31 + ipAddr.hashCode()) * 31 + port;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }

            if (!(o instanceof PoolKey)) {
                return false;
            }

            final PoolKey that = (PoolKey) o;
            // Compare IP address first, which is most likely to differ.
            return ipAddr.equals(that.ipAddr) &&
                   port == that.port &&
                   host.equals(that.host);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("host", host)
                              .add("ipAddr", ipAddr)
                              .add("port", port)
                              .toString();
        }
    }

    /**
     * http 2.0 池化Channel的具体实现
     *
     * 该类需要实现release();
     */
    static final class Http2PooledChannel extends PooledChannel {
        Http2PooledChannel(Channel channel, SessionProtocol protocol) {
            super(channel, protocol);
        }

        @Override
        public void release() {
            // There's nothing to do here because we keep the connection in the pool after acquisition.
            // 当我们获取到拿到链接以后，会让连接一直在池子里面
        }
    }

    /**
     * http 1.0 池化Channel的具体实现
     *
     * 该类需要实现release();
     */
    final class Http1PooledChannel extends PooledChannel {
        private final PoolKey key;

        Http1PooledChannel(Channel channel, SessionProtocol protocol, PoolKey key) {
            super(channel, protocol);
            this.key = key;
        }

        @Override
        public void release() {
            if (!eventLoop.inEventLoop()) {
                eventLoop.execute(this::doRelease);
            } else {
                doRelease();
            }
        }

        /**
         * 对当前channel进行健康检查，如果健康则将其加入pool队尾，否则不做任何的处理
         */
        private void doRelease() {
            // 检查当前连接是否健康
            if (isHealthy(this)) {
                // Channel turns out to be healthy. Add it back to the pool.
                // 如果体检发现很正常, 则要将其加入到pool的队尾
                addToPool(protocol(), key, this);
            } else {
                // Channel not healthy. Do not add it back to the pool.
                // 如果发现该channel不健康，则不必将其加入pool中了。
            }
        }
    }

    public static void main(String[] args) {
        Map<PoolKey, Deque<PooledChannel>>[] pool = newEnumMap(
                Map.class,
                unused -> new HashMap<>(),
                SessionProtocol.H1, SessionProtocol.H1C,
                SessionProtocol.H2, SessionProtocol.H2C);
        for(Map<PoolKey, Deque<PooledChannel>> map: pool){
            System.out.println(map);
            for (PoolKey key: map.keySet()){
                System.out.println(key.toString() + " = " + map.get(key));
            }
            System.out.println(" ************ ");
        }
    }
}
