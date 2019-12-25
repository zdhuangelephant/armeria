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
package com.linecorp.armeria.client;

import static com.linecorp.armeria.common.SessionProtocol.H1;
import static com.linecorp.armeria.common.SessionProtocol.H1C;
import static com.linecorp.armeria.common.SessionProtocol.H2;
import static com.linecorp.armeria.common.SessionProtocol.H2C;
import static com.linecorp.armeria.common.stream.SubscriptionOption.WITH_POOLED_OBJECTS;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.ScheduledFuture;

import javax.annotation.Nullable;

import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.HttpResponseDecoder.HttpResponseWrapper;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.Http1ObjectEncoder;
import com.linecorp.armeria.internal.Http2ObjectEncoder;
import com.linecorp.armeria.internal.HttpObjectEncoder;
import com.linecorp.armeria.internal.InboundTrafficController;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.ChannelInputShutdownReadComplete;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2ConnectionPrefaceAndSettingsFrameWrittenEvent;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.ssl.SslCloseCompletionEvent;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Promise;

/**
 *ChannelDuplexHandler其继承自Inbound&Outbound, 适合有拦截操作或者装填变更的操作
 *
 * HttpSessionHandler
 */
final class HttpSessionHandler extends ChannelDuplexHandler implements HttpSession {

    private static final Logger logger = LoggerFactory.getLogger(HttpSessionHandler.class);

    /**
     * 2^29 - We could have used 2^30 but this should be large enough.
     */
    private static final int MAX_NUM_REQUESTS_SENT = 536870912;

    // 连接池的声明
    private final HttpChannelPool channelPool;
    // 当前channel的声明
    private final Channel channel;
    // sessionPromise的声明
    private final Promise<Channel> sessionPromise;
    // 超时调度器
    private final ScheduledFuture<?> sessionTimeoutFuture;

    /**
     * Whether the current channel is active or not.
     * <br/>
     * 当前channel激活状态与否， 分别在channelactive的时候设置为true; channelInavtive的时候设置为false
     */
    private volatile boolean active;

    /**
     * The current negotiated {@link SessionProtocol}.
     * <br/>
     * 当前的Channel的SessionProtocol
     */
    @Nullable
    private SessionProtocol protocol;

    // Http响应解码器
    @Nullable
    private HttpResponseDecoder responseDecoder;
    // requestEncoder可能是Http1ObjectEncoder也可能是Http2ObjectEncoder
    @Nullable
    private HttpObjectEncoder requestEncoder;

    /**
     * The maximum number of unfinished requests. In HTTP/2, this value is identical to MAX_CONCURRENT_STREAMS.
     * In HTTP/1, this value stays at {@link Integer#MAX_VALUE}.
     * <br/>
     * 未处理的请求的最大阈值， 在Http2内它的值就和MAX_CONCURRENT_STREAMS是一样的。
     */
    private int maxUnfinishedResponses = Integer.MAX_VALUE;

    /**
     * The number of requests sent. Disconnects when it reaches at {@link #MAX_NUM_REQUESTS_SENT}.
     * <br/>
     * 当前channel发送的req个数， 当它达到{@link #MAX_NUM_REQUESTS_SENT}，该channel将会自动断开连接
     */
    private int numRequestsSent;

    /**
     * {@code true} if the protocol upgrade to HTTP/2 has failed.
     * If set to {@code true}, another connection attempt will follow.
     * <br/>
     * true： 如果协议升级到HTTP/2已经失败了
     */
    private boolean needsRetryWithH1C;

    HttpSessionHandler(HttpChannelPool channelPool, Channel channel,
                       Promise<Channel> sessionPromise, ScheduledFuture<?> sessionTimeoutFuture) {

        this.channelPool = requireNonNull(channelPool, "channelPool");
        this.channel = requireNonNull(channel, "channel");
        this.sessionPromise = requireNonNull(sessionPromise, "sessionPromise");
        this.sessionTimeoutFuture = requireNonNull(sessionTimeoutFuture, "sessionTimeoutFuture");
    }

    @Override
    public SessionProtocol protocol() {
        return protocol;
    }

    @Override
    public InboundTrafficController inboundTrafficController() {
        assert responseDecoder != null;
        return responseDecoder.inboundTrafficController();
    }

    @Override
    public int unfinishedResponses() {
        assert responseDecoder != null;
        return responseDecoder.unfinishedResponses();
    }

    @Override
    public int maxUnfinishedResponses() {
        return maxUnfinishedResponses;
    }

    @Override
    public boolean canSendRequest() {
        assert responseDecoder != null;
        return active && !responseDecoder.needsToDisconnectWhenFinished();
    }

    @Override
    public boolean invoke(ClientRequestContext ctx, HttpRequest req, DecodedHttpResponse res) {
        // 处理res已经提前取消了的场景
        if (handleEarlyCancellation(ctx, req, res)) {
            return true;
        }


        final long writeTimeoutMillis = ctx.writeTimeoutMillis();
        final long responseTimeoutMillis = ctx.responseTimeoutMillis();
        final long maxContentLength = ctx.maxResponseLength();

        assert responseDecoder != null;
        assert requestEncoder != null;

        final int numRequestsSent = ++this.numRequestsSent;
        final HttpResponseWrapper wrappedRes =
                responseDecoder.addResponse(numRequestsSent, req, res, ctx.logBuilder(),
                                            responseTimeoutMillis, maxContentLength);
        /**
         * TODO 重点之中！！
         * req.subscribe(xxx); 该方法的一旦调用完毕， 就立即会触发{@link HttpRequestSubscriber#onSubscribe(Subscription)}方法的调用
         */
        req.subscribe(
                new HttpRequestSubscriber(channel, requestEncoder,
                                          numRequestsSent, req, wrappedRes, ctx,
                                          writeTimeoutMillis),
                channel.eventLoop(), WITH_POOLED_OBJECTS);

        // 当前channel的处理连接的数量已经达到了阈值， 所以在最后一个请求处理完毕以后，需要关闭掉
        if (numRequestsSent >= MAX_NUM_REQUESTS_SENT) {
            responseDecoder.disconnectWhenFinished();
            return false;
        } else {
            return true;
        }
    }

    /**
     * 对于提前取消的处理 补充逻辑
     * @param ctx
     * @param req
     * @param res
     * @return
     */
    private boolean handleEarlyCancellation(ClientRequestContext ctx, HttpRequest req,
                                            DecodedHttpResponse res) {
        // 如果该StreamMessage还能用的话，则代码就无需向下走了
        if (res.isOpen()) {
            return false;
        }
        // 如果res已经不能用了， 则首先将req通过AbortedStreamException来做快速失败的响应处理。

        // The response has been closed even before its request is sent.
        assert protocol != null;

        // 时req的快速抛出AbortedStreamException
        req.abort();
        ctx.logBuilder().startRequest(channel, protocol);
        ctx.logBuilder().requestHeaders(req.headers());

        // 如下的分别是对req、res做异步处理

        // 对req做异步结果处理，其处理方式为记录log
        req.completionFuture().handle((unused, cause) -> {
            if (cause == null) {
                ctx.logBuilder().endRequest();
            } else {
                ctx.logBuilder().endRequest(cause);
            }
            return null;
        });

        // 对res做异步结果处理，其处理方式为记录log
        res.completionFuture().handle((unused, cause) -> {
            if (cause == null) {
                ctx.logBuilder().endResponse();
            } else {
                ctx.logBuilder().endResponse(cause);
            }
            return null;
        });

        return true;
    }

    @Override
    public void retryWithH1C() {
        needsRetryWithH1C = true;
    }

    @Override
    public void deactivate() {
        active = false;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        active = channel.isActive();
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        active = true;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof Http2Settings) {
            final Long maxConcurrentStreams = ((Http2Settings) msg).maxConcurrentStreams();
            if (maxConcurrentStreams != null) {
                maxUnfinishedResponses =
                        maxConcurrentStreams > Integer.MAX_VALUE ? Integer.MAX_VALUE
                                                                 : maxConcurrentStreams.intValue();
            } else {
                maxUnfinishedResponses = Integer.MAX_VALUE;
            }
            return;
        }

        // Handle an unexpected message by raising an exception with debugging information.
        try {
            final String typeInfo;
            if (msg instanceof ByteBuf) {
                typeInfo = msg + " HexDump: " + ByteBufUtil.hexDump((ByteBuf) msg);
            } else {
                typeInfo = String.valueOf(msg);
            }
            throw new IllegalStateException("unexpected message type: " + typeInfo);
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof SessionProtocol) {
            assert protocol == null;
            assert responseDecoder == null;

            sessionTimeoutFuture.cancel(false);

            // Set the current protocol and its associated WaitsHolder implementation.
            final SessionProtocol protocol = (SessionProtocol) evt;
            this.protocol = protocol;
            if (protocol == H1 || protocol == H1C) {
                requestEncoder = new Http1ObjectEncoder(channel, false, protocol.isTls());
                responseDecoder = ctx.pipeline().get(Http1ResponseDecoder.class);
            } else if (protocol == H2 || protocol == H2C) {
                final Http2ConnectionHandler handler = ctx.pipeline().get(Http2ConnectionHandler.class);
                requestEncoder = new Http2ObjectEncoder(ctx, handler.encoder());
                responseDecoder = ctx.pipeline().get(Http2ClientConnectionHandler.class).responseDecoder();
            } else {
                throw new Error(); // Should never reach here.
            }

            if (!sessionPromise.trySuccess(channel)) {
                // Session creation has been failed already; close the connection.
                ctx.close();
            }
            return;
        }

        if (evt instanceof SessionProtocolNegotiationException) {
            sessionTimeoutFuture.cancel(false);
            sessionPromise.tryFailure((SessionProtocolNegotiationException) evt);
            ctx.close();
            return;
        }

        if (evt instanceof Http2ConnectionPrefaceAndSettingsFrameWrittenEvent ||
            evt instanceof SslHandshakeCompletionEvent ||
            evt instanceof SslCloseCompletionEvent ||
            evt instanceof ChannelInputShutdownReadComplete) {
            // Expected events
            return;
        }

        logger.warn("{} Unexpected user event: {}", channel, evt);
    }

    // Channel关闭的时候
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // 将标志位置为false，已经"死亡"
        active = false;

        // 协议更新失败， 但是需要重新retry
        // Protocol upgrade has failed, but needs to retry.
        if (needsRetryWithH1C) {
            assert responseDecoder == null || !responseDecoder.hasUnfinishedResponses();
            sessionTimeoutFuture.cancel(false);
            channelPool.connect(channel.remoteAddress(), H1C, sessionPromise);
        } else {
            // 将所有正在等待着的请求都做快速失败的处理
            // Fail all pending responses.
            failUnfinishedResponses(ClosedSessionException.get());

            // Cancel the timeout and reject the sessionPromise just in case the connection has been closed
            // even before the session protocol negotiation is done.
            // 取消超时，并且拒绝掉sessionPromise，一旦连接被关闭的情况下
            sessionTimeoutFuture.cancel(false);
            sessionPromise.tryFailure(ClosedSessionException.get());
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        Exceptions.logIfUnexpected(logger, channel, protocol(), cause);
        if (channel.isActive()) {
            ctx.close();
        }
    }

    private void failUnfinishedResponses(Throwable e) {
        final HttpResponseDecoder responseDecoder = this.responseDecoder;
        if (responseDecoder == null) {
            return;
        }

        responseDecoder.failUnfinishedResponses(e);
    }
}
