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

import java.net.InetSocketAddress;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.HttpResponseDecoder.HttpResponseWrapper;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.common.stream.ClosedPublisherException;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.HttpObjectEncoder;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.util.ReferenceCountUtil;

/**
 * http异步响应结果的Subscriber者
 */
final class HttpRequestSubscriber implements Subscriber<HttpObject>, ChannelFutureListener {

    private static final Logger logger = LoggerFactory.getLogger(HttpRequestSubscriber.class);

    enum State {
        NEEDS_TO_WRITE_FIRST_HEADER,
        NEEDS_DATA_OR_TRAILERS,
        DONE
    }

    private final Channel ch;
    private final HttpObjectEncoder encoder;
    private final int id;
    private final HttpRequest request;
    private final HttpResponseWrapper response;
    private final ClientRequestContext reqCtx;
    private final RequestLogBuilder logBuilder;
    private final long timeoutMillis;
    /**
     * 这才是 发布者和订阅者的关系维护者
     */
    @Nullable
    private Subscription subscription;
    @Nullable
    private ScheduledFuture<?> timeoutFuture;
    private State state = State.NEEDS_TO_WRITE_FIRST_HEADER;
    private boolean isSubscriptionCompleted;

    private boolean loggedRequestFirstBytesTransferred;

    HttpRequestSubscriber(Channel ch, HttpObjectEncoder encoder,
                          int id, HttpRequest request, HttpResponseWrapper response,
                          ClientRequestContext reqCtx, long timeoutMillis) {

        this.ch = ch;
        this.encoder = encoder;
        this.id = id;
        this.request = request;
        this.response = response;
        this.reqCtx = reqCtx;
        logBuilder = reqCtx.logBuilder();
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * Invoked on each write of an {@link HttpObject}.
     * 在每次写{@link HttpObject}的时候都会被调用。
     */
    @Override
    public void operationComplete(ChannelFuture future) throws Exception {
        // 如果message已经被发送出去了，为了重新开启一个请求，需要取消预设定超时任务。
        // If a message has been sent out, cancel the timeout for starting a request.
        cancelTimeout();

        if (future.isSuccess()) {
            // 第一次写总是第一个头，所以我们第一次完成了转变
            // The first write is always the first headers, so log that we finished our first transfer over the
            // wire.
            if (!loggedRequestFirstBytesTransferred) {
                logBuilder.requestFirstBytesTransferred();
                loggedRequestFirstBytesTransferred = true;
            }

            if (state == State.DONE) {
                // Successfully sent the request; schedule the response timeout.
                // 请求已发送成功，预设定超时任务。
                response.scheduleTimeout(ch.eventLoop());
            }


            /*
              请求更多的消息，不管当前的state是不是DONE。当没有更多的消息被生产出来的时候，它可以使生产者有机会去发起最后的诸如'onComplete'和'onError'的调用。
             */
            // Request more messages regardless whether the state is DONE. It makes the producer have
            // a chance to produce the last call such as 'onComplete' and 'onError' when there are
            // no more messages it can produce.
            if (!isSubscriptionCompleted) {
                assert subscription != null;
                /**
                 * 下面的方法一旦调用到，则将瞬间从发布者向订阅者发布消息
                 */
                subscription.request(1);
            }
            return;
        }

        // 代码执行到这，说明future失败了。

        fail(future.cause());

        // 如果 cause 并不是 ClosedPublisherException的类型的话。
        final Throwable cause = future.cause();
        if (!(cause instanceof ClosedPublisherException)) {
            final Channel ch = future.channel();
            Exceptions.logIfUnexpected(logger, ch, HttpSession.get(ch).protocol(), cause);
            ch.close();
        }
    }

    /**
     * Invoked after calling {@link Publisher#subscribe(Subscriber)}.
     * <br/>
     * 即{@link com.linecorp.armeria.client.HttpSessionHandler#invoke(ClientRequestContext, HttpRequest, DecodedHttpResponse)}的req.subscribe(xxx)调用完毕以后，紧接着此方法就会被异步调用
     * @param subscription
     */
    @Override
    public void onSubscribe(Subscription subscription) {
        assert this.subscription == null;
        // 告诉当前的subscribe 你要订阅谁的数据消息
        this.subscription = subscription;

        final EventLoop eventLoop = ch.eventLoop();
        if (timeoutMillis > 0) {
            // The timer would be executed if the first message has not been sent out within the timeout.
            // 定时任务将会被调度到， 如果message没有在指定的超时时间内过来。
            timeoutFuture = eventLoop.schedule(
                    () -> failAndRespond(WriteTimeoutException.get()),
                    timeoutMillis, TimeUnit.MILLISECONDS);
        }

        /*
         * 注意:
         *  在这个方法地步的代码一定会被调用到，否则在这类中的回调方法会在subscription and timeoutFuture被初始化之前调用。
         *  它是因为成功的写入第一个头，将会触发subscription.request(1)的调用。
         */
        // NB: This must be invoked at the end of this method because otherwise the callback methods in this
        //     class can be called before the member fields (subscription and timeoutFuture) are initialized.
        //     It is because the successful write of the first headers will trigger subscription.request(1).
        writeFirstHeader();
    }

    private void writeFirstHeader() {
        final HttpSession session = HttpSession.get(ch);
        if (!session.canSendRequest()) {
            failAndRespond(new UnprocessedRequestException(ClosedSessionException.get()));
            return;
        }

        final RequestHeaders firstHeaders = autoFillHeaders(ch);

        final SessionProtocol protocol = session.protocol();
        assert protocol != null;
        logBuilder.startRequest(ch, protocol);
        logBuilder.requestHeaders(firstHeaders);

        if (request.isEmpty()) {
            state = State.DONE;
            write0(firstHeaders, true, true);
        } else {
            state = State.NEEDS_DATA_OR_TRAILERS;
            write0(firstHeaders, false, true);
        }
    }

    private RequestHeaders autoFillHeaders(Channel ch) {
        final RequestHeadersBuilder requestHeaders = request.headers().toBuilder();
        final HttpHeaders additionalHeaders = reqCtx.additionalRequestHeaders();
        if (!additionalHeaders.isEmpty()) {
            requestHeaders.setIfAbsent(additionalHeaders);
        }

        final SessionProtocol sessionProtocol = reqCtx.sessionProtocol();
        if (requestHeaders.authority() == null) {
            final InetSocketAddress isa = (InetSocketAddress) ch.remoteAddress();
            final String hostname = isa.getHostName();
            final int port = isa.getPort();

            final String authority;
            if (port == sessionProtocol.defaultPort()) {
                authority = hostname;
            } else {
                final StringBuilder buf = new StringBuilder(hostname.length() + 6);
                buf.append(hostname);
                buf.append(':');
                buf.append(port);
                authority = buf.toString();
            }

            requestHeaders.add(HttpHeaderNames.AUTHORITY, authority);
        }

        if (!requestHeaders.contains(HttpHeaderNames.SCHEME)) {
            requestHeaders.add(HttpHeaderNames.SCHEME, sessionProtocol.isTls() ? "https" : "http");
        }

        if (!requestHeaders.contains(HttpHeaderNames.USER_AGENT)) {
            requestHeaders.add(HttpHeaderNames.USER_AGENT, HttpHeaderUtil.USER_AGENT.toString());
        }
        return requestHeaders.build();
    }

    /**
     * 当前订阅者，收到了订阅对象发送过来的数据报文
     * @param o
     */
    @Override
    public void onNext(HttpObject o) {
        if (!(o instanceof HttpData) && !(o instanceof HttpHeaders)) {
            throw newIllegalStateException(
                    "published an HttpObject that's neither Http2Headers nor Http2Data: " + o);
        }

        boolean endOfStream = o.isEndOfStream();
        switch (state) {
            case NEEDS_DATA_OR_TRAILERS: {
                if (o instanceof HttpHeaders) {
                    final HttpHeaders trailers = (HttpHeaders) o;
                    if (trailers.contains(HttpHeaderNames.STATUS)) {
                        throw newIllegalStateException("published a trailers with status: " + o);
                    }
                    // Trailers always end the stream even if not explicitly set.
                    endOfStream = true;
                    logBuilder.requestTrailers(trailers);
                } else {
                    logBuilder.increaseRequestLength((HttpData) o);
                }
                write(o, endOfStream, true);
                break;
            }
            case DONE:
                // 当state变成了DONE后，任何来到这里的消息，都会触发当前Subscriber的cancel的动作。
                // Cancel the subscription if any message comes here after the state has been changed to DONE.
                cancelSubscription();
                ReferenceCountUtil.safeRelease(o);
                break;
        }
    }

    @Override
    public void onError(Throwable cause) {
        isSubscriptionCompleted = true;
        failAndRespond(cause);
    }

    @Override
    public void onComplete() {
        isSubscriptionCompleted = true;
        cancelTimeout();

        if (state != State.DONE) {
            write(HttpData.EMPTY_DATA, true, true);
        }
    }

    private void write(HttpObject o, boolean endOfStream, boolean flush) {
        if (!ch.isActive()) {
            ReferenceCountUtil.safeRelease(o);
            fail(ClosedSessionException.get());
            return;
        }

        if (endOfStream) {
            state = State.DONE;
        }

        write0(o, endOfStream, flush);
    }

    private void write0(HttpObject o, boolean endOfStream, boolean flush) {
        final ChannelFuture future;
        if (o instanceof HttpHeaders) {
            future = encoder.writeHeaders(id, streamId(), (HttpHeaders) o, endOfStream);
        } else {
            future = encoder.writeData(id, streamId(), (HttpData) o, endOfStream);
        }

        if (endOfStream) {
            logBuilder.endRequest();
        }

        future.addListener(this);
        if (flush) {
            ch.flush();
        }
    }

    private int streamId() {
        return (id << 1) + 1;
    }

    private void fail(Throwable cause) {
        state = State.DONE;
        logBuilder.endRequest(cause);
        logBuilder.endResponse(cause);
        cancelSubscription();
    }

    private void cancelSubscription() {
        isSubscriptionCompleted = true;
        assert subscription != null;
        subscription.cancel();
    }

    private void failAndRespond(Throwable cause) {
        fail(cause);

        final Http2Error error;
        if (response.isOpen()) {
            response.close(cause);
            error = Http2Error.INTERNAL_ERROR;
        } else if (cause instanceof WriteTimeoutException || cause instanceof AbortedStreamException) {
            error = Http2Error.CANCEL;
        } else {
            Exceptions.logIfUnexpected(logger, ch,
                                       HttpSession.get(ch).protocol(),
                                       "a request publisher raised an exception", cause);
            error = Http2Error.INTERNAL_ERROR;
        }

        if (ch.isActive()) {
            encoder.writeReset(id, streamId(), error);
            ch.flush();
        }
    }

    private boolean cancelTimeout() {
        final ScheduledFuture<?> timeoutFuture = this.timeoutFuture;
        if (timeoutFuture == null) {
            return true;
        }

        this.timeoutFuture = null;
        return timeoutFuture.cancel(false);
    }

    private IllegalStateException newIllegalStateException(String msg) {
        final IllegalStateException cause = new IllegalStateException(msg);
        fail(cause);
        return cause;
    }
}
