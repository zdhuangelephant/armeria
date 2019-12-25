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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.stream.CancelledSubscriptionException;
import com.linecorp.armeria.common.stream.StreamWriter;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.InboundTrafficController;

import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.collection.IntObjectMap;
import io.netty.util.concurrent.ScheduledFuture;

/**
 * HttpResponseDecoder 响应解码器
 */
abstract class HttpResponseDecoder {

    private static final Logger logger = LoggerFactory.getLogger(HttpResponseDecoder.class);

    private final IntObjectMap<HttpResponseWrapper> responses = new IntObjectHashMap<>();
    private final Channel channel;
    private final InboundTrafficController inboundTrafficController;
    private boolean disconnectWhenFinished;

    HttpResponseDecoder(Channel channel, InboundTrafficController inboundTrafficController) {
        this.channel = channel;
        this.inboundTrafficController = inboundTrafficController;
    }

    final Channel channel() {
        return channel;
    }

    final InboundTrafficController inboundTrafficController() {
        return inboundTrafficController;
    }

    HttpResponseWrapper addResponse(
            int id, @Nullable HttpRequest req, DecodedHttpResponse res, RequestLogBuilder logBuilder,
            long responseTimeoutMillis, long maxContentLength) {

        final HttpResponseWrapper newRes =
                new HttpResponseWrapper(req, res, logBuilder, responseTimeoutMillis, maxContentLength);
        final HttpResponseWrapper oldRes = responses.put(id, newRes);

        assert oldRes == null : "addResponse(" + id + ", " + res + ", " + responseTimeoutMillis + "): " +
                                oldRes;

        return newRes;
    }

    @Nullable
    final HttpResponseWrapper getResponse(int id) {
        return responses.get(id);
    }

    @Nullable
    final HttpResponseWrapper getResponse(int id, boolean remove) {
        return remove ? removeResponse(id) : getResponse(id);
    }

    @Nullable
    final HttpResponseWrapper removeResponse(int id) {
        return responses.remove(id);
    }

    final int unfinishedResponses() {
        return responses.size();
    }

    final boolean hasUnfinishedResponses() {
        return !responses.isEmpty();
    }

    /**
     * 快速失败
     * @param cause
     */
    final void failUnfinishedResponses(Throwable cause) {
        try {
            for (HttpResponseWrapper res : responses.values()) {
                res.close(cause);
            }
        } finally {
            responses.clear();
        }
    }

    final void disconnectWhenFinished() {
        disconnectWhenFinished = true;
    }

    final boolean needsToDisconnectNow() {
        return disconnectWhenFinished && !hasUnfinishedResponses();
    }

    final boolean needsToDisconnectWhenFinished() {
        return disconnectWhenFinished;
    }


    /**
     * 定义一个StreamWriter的实现子类。其类还是一个Runnable的实现类。
     * 其实代理了某个实现类，即是个Response的封装类
     */
    static final class HttpResponseWrapper implements StreamWriter<HttpObject>, Runnable {

        enum State {
            // 没有消息的等待
            WAIT_NON_INFORMATIONAL,
            // 没有数据、没有trailers的等待
            WAIT_DATA_OR_TRAILERS,
            // 已完成
            DONE
        }

        @Nullable
        private final HttpRequest request;

        // 被代理的Response
        private final DecodedHttpResponse delegate;
        // 日志记录
        private final RequestLogBuilder logBuilder;
        // 响应超时的时间 ms
        private final long responseTimeoutMillis;
        // 最大的报文长度
        private final long maxContentLength;
        // 响应超时的处理调度器
        @Nullable
        private ScheduledFuture<?> responseTimeoutFuture;

        private boolean loggedResponseFirstBytesTransferred;

        private State state = State.WAIT_NON_INFORMATIONAL;

        HttpResponseWrapper(@Nullable HttpRequest request, DecodedHttpResponse delegate,
                            RequestLogBuilder logBuilder, long responseTimeoutMillis, long maxContentLength) {
            this.request = request;
            this.delegate = delegate;
            this.logBuilder = logBuilder;
            this.responseTimeoutMillis = responseTimeoutMillis;
            this.maxContentLength = maxContentLength;
        }

        // 返回代理的completionFuture
        CompletableFuture<Void> completionFuture() {
            return delegate.completionFuture();
        }

        /**
         * 调度一个超时任务
         * @param eventLoop
         */
        void scheduleTimeout(EventLoop eventLoop) {
            if (responseTimeoutFuture != null || responseTimeoutMillis <= 0 || !isOpen()) {
                /*
                    如下的场景是不需要再次调度响应超时的任务:
                    - 任务已经被调度过了
                    - 超时时间已经被禁用了 或者 响应流已经被取消掉了
                 */

                // No need to schedule a response timeout if:
                // - the timeout has been scheduled already,
                // - the timeout has been disabled or
                // - the response stream has been closed already.
                return;
            }

            // 对超时任务设，进行超时调度。
            responseTimeoutFuture = eventLoop.schedule(
                    this, responseTimeoutMillis, TimeUnit.MILLISECONDS);
        }

        /**
         * 取消超时任务
         * @return
         */
        boolean cancelTimeout() {
            final ScheduledFuture<?> responseTimeoutFuture = this.responseTimeoutFuture;
            if (responseTimeoutFuture == null) {
                return true;
            }

            this.responseTimeoutFuture = null;
            return responseTimeoutFuture.cancel(false);
        }

        long maxContentLength() {
            return maxContentLength;
        }

        long writtenBytes() {
            return delegate.writtenBytes();
        }

        void logResponseFirstBytesTransferred() {
            if (!loggedResponseFirstBytesTransferred) {
                logBuilder.responseFirstBytesTransferred();
                loggedResponseFirstBytesTransferred = true;
            }
        }

        @Override
        public void run() {
            // 获取响应超时的异常实例
            final ResponseTimeoutException cause = ResponseTimeoutException.get();
            delegate.close(cause);
            logBuilder.endResponse(cause);

            if (request != null) {
                request.abort();
            }
        }

        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        /**
         * Writes the specified {@link HttpObject} to {@link DecodedHttpResponse}. This method is only called
         * from {@link Http1ResponseDecoder} and {@link Http2ResponseDecoder}. If this returns {@code false},
         * it means the response stream has been closed due to disconnection or by the response consumer.
         * So the caller do not need to handle such cases because it will be notified to the response
         * consumer anyway.
         *
         * <br/>
         * 写指定的{@link HttpObject}到{@link DecodedHttpResponse}。这个方法仅仅会在{@link Http1ResponseDecoder}和{@link Http2ResponseDecoder}两者内发起调用。
         * 如果返回的是false: 它意味着由于被这个response的订阅者断开连接导致响应流被取消。但是调用者却不需要处理此问题，因为它总会被传递到该response的订阅者那边。
         *
         */
        @Override
        public boolean tryWrite(HttpObject o) {
            switch (state) {
                case WAIT_NON_INFORMATIONAL:
                    // 注意: 尽管调用logBuilder.startResponse()多次， 但是它总是安全的。
                    // NB: It's safe to call logBuilder.startResponse() multiple times.
                    // 开始记录当前响应时间
                    logBuilder.startResponse();

                    assert o instanceof HttpHeaders && !(o instanceof RequestHeaders) : o;

                    if (o instanceof ResponseHeaders) {
                        final ResponseHeaders headers = (ResponseHeaders) o;
                        final HttpStatus status = headers.status();
                        if (status.codeClass() != HttpStatusClass.INFORMATIONAL) {
                            state = State.WAIT_DATA_OR_TRAILERS;
                            logBuilder.responseHeaders(headers);
                        }
                    }
                    break;
                case WAIT_DATA_OR_TRAILERS:
                    if (o instanceof HttpHeaders) {
                        state = State.DONE;
                        logBuilder.responseTrailers((HttpHeaders) o);
                    } else {
                        logBuilder.increaseResponseLength((HttpData) o);
                    }
                    break;
                case DONE:
                    ReferenceCountUtil.safeRelease(o);
                    return false;
            }
            return delegate.tryWrite(o);
        }

        @Override
        public boolean tryWrite(Supplier<? extends HttpObject> o) {
            return delegate.tryWrite(o);
        }

        @Override
        public CompletableFuture<Void> onDemand(Runnable task) {
            return delegate.onDemand(task);
        }

        void onSubscriptionCancelled(@Nullable Throwable cause) {
            close(cause, this::cancelAction);
        }

        @Override
        public void close() {
            close(null, this::closeAction);
        }

        @Override
        public void close(Throwable cause) {
            close(cause, this::closeAction);
        }

        private void close(@Nullable Throwable cause,
                           Consumer<Throwable> actionOnTimeoutCancelled) {
            state = State.DONE;
            if (cancelTimeout()) {
                actionOnTimeoutCancelled.accept(cause);
            } else {
                if (cause != null && !Exceptions.isExpected(cause)) {
                    logger.warn("Unexpected exception:", cause);
                }
            }

            if (request != null) {
                request.abort();
            }
        }

        private void closeAction(@Nullable Throwable cause) {
            if (cause != null) {
                delegate.close(cause);
                logBuilder.endResponse(cause);
            } else {
                delegate.close();
                logBuilder.endResponse();
            }
        }

        private void cancelAction(@Nullable Throwable cause) {
            if (cause != null && !(cause instanceof CancelledSubscriptionException)) {
                logBuilder.endResponse(cause);
            } else {
                logBuilder.endResponse();
            }
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }
}
