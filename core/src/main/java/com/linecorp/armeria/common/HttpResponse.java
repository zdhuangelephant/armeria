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

package com.linecorp.armeria.common;

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.armeria.common.HttpResponseUtil.delegateWhenStageComplete;
import static com.linecorp.armeria.internal.ArmeriaHttpUtil.isContentAlwaysEmpty;
import static com.linecorp.armeria.internal.ArmeriaHttpUtil.setOrRemoveContentLength;
import static java.util.Objects.requireNonNull;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Formatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;

import com.linecorp.armeria.common.FixedHttpResponse.OneElementFixedHttpResponse;
import com.linecorp.armeria.common.FixedHttpResponse.RegularFixedHttpResponse;
import com.linecorp.armeria.common.FixedHttpResponse.TwoElementFixedHttpResponse;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.SubscriptionOption;

import io.netty.buffer.ByteBufAllocator;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.EventExecutor;

/**
 * A streamed HTTP/2 {@link Response}.
 * 一个流式Publisher， 只允许有一个订阅者，并且只允许传输一次的响应结果
 */
public interface HttpResponse extends Response, StreamMessage<HttpObject> {

    // Note: Ensure we provide the same set of `of()` methods with the `of()` methods of
    //       AggregatedHttpResponse for consistency.

    /**
     * Creates a new HTTP response that can stream an arbitrary number of {@link HttpObject} to the client.
     * The first object written must be of type {@link ResponseHeaders}.
     * <br/>
     * 创建一个新的HttpResponse，它可以流出任意个{@link HttpObject}到客户端，注意一点的是：第一个被写出的对象总是{@link ResponseHeaders}.
     */
    static HttpResponseWriter streaming() {
        return new DefaultHttpResponse();
    }

    /**
     * Creates a new HTTP response that delegates to the {@link HttpResponse} produced by the specified
     * {@link CompletionStage}. If the specified {@link CompletionStage} fails, the returned response will be
     * closed with the same cause as well.
     * <br/>
     * 创建一个HttpResponse，它是产自于传入的stage参数，如果stage失败了，那么返回的response也会因为同样的异常原因而被关闭.
     *
     * @param stage the {@link CompletionStage} which will produce the actual {@link HttpResponse}  其将会产生{@link HttpResponse}
     */
    static HttpResponse from(CompletionStage<? extends HttpResponse> stage) {
        final DeferredHttpResponse res = new DeferredHttpResponse();
        delegateWhenStageComplete(stage, res);
        return res;
    }

    /**
     * Creates a new HTTP response that delegates to the {@link HttpResponse} produced by the specified
     * {@link CompletionStage}. If the specified {@link CompletionStage} fails, the returned response will be
     * closed with the same cause as well.
     * <br/>
     * 创建一个HttpResponse，它是产自于传入的stage参数，如果stage失败了，那么返回的response也会因为同样的异常原因而被关闭.
     *
     * @param stage the {@link CompletionStage} which will produce the actual {@link HttpResponse}
     * @param subscriberExecutor the {@link EventExecutor} which will be used when a user subscribes
     *                           the returned {@link HttpResponse} using {@link #subscribe(Subscriber)}
     *                           or {@link #subscribe(Subscriber, SubscriptionOption...)}.
     */
    static HttpResponse from(CompletionStage<? extends HttpResponse> stage,
                             EventExecutor subscriberExecutor) {
        requireNonNull(subscriberExecutor, "subscriberExecutor");
        final DeferredHttpResponse res = new DeferredHttpResponse(subscriberExecutor);
        delegateWhenStageComplete(stage, res);
        return res;
    }

    /**
     * Creates a new HTTP response that delegates to the provided {@link AggregatedHttpResponse}, beginning
     * publishing after {@code delay} has passed from a random {@link ScheduledExecutorService}.
     * <br/>
     * 创建一个HttpResponse，它是产自于传入的{@link AggregatedHttpResponse}response，在指定的delay延迟过后，开始publish消息
     */
    static HttpResponse delayed(AggregatedHttpResponse response, Duration delay) {
        requireNonNull(response, "response");
        requireNonNull(delay, "delay");
        return delayed(HttpResponse.of(response), delay);
    }

    /**
     * Creates a new HTTP response that delegates to the provided {@link AggregatedHttpResponse}, beginning
     * publishing after {@code delay} has passed from the provided {@link ScheduledExecutorService}.
     * <br/>
     * 创建一个HttpResponse，它是产自于传入的{@link AggregatedHttpResponse}response，在指定的delay延迟过后，开始publish消息
     */
    static HttpResponse delayed(AggregatedHttpResponse response, Duration delay,
                                ScheduledExecutorService executor) {
        requireNonNull(response, "response");
        requireNonNull(delay, "delay");
        requireNonNull(executor, "executor");
        return delayed(HttpResponse.of(response), delay, executor);
    }

    /**
     * Creates a new HTTP response that delegates to the provided {@link HttpResponse}, beginning publishing
     * after {@code delay} has passed from a random {@link ScheduledExecutorService}.
     * <br/>
     * 创建一个HttpResponse，它是产自于传入的{@link HttpResponse}response，在指定的delay延迟过后，开始publish消息
     */
    static HttpResponse delayed(HttpResponse response, Duration delay) {
        requireNonNull(response, "response");
        requireNonNull(delay, "delay");
        return delayed(response, delay, CommonPools.workerGroup().next());
    }

    /**
     * Creates a new HTTP response that delegates to the provided {@link HttpResponse}, beginning publishing
     * after {@code delay} has passed from the provided {@link ScheduledExecutorService}.
     * <br/>
     * 创建一个HttpResponse，它是产自于传入的{@link HttpResponse}response，在指定的delay延迟过后，开始publish消息(通过传入的线程池来异步执行)
     */
    static HttpResponse delayed(HttpResponse response, Duration delay, ScheduledExecutorService executor) {
        requireNonNull(response, "response");
        requireNonNull(delay, "delay");
        requireNonNull(executor, "executor");
        final DeferredHttpResponse res = new DeferredHttpResponse();
        executor.schedule(() -> res.delegate(response), delay.toNanos(), TimeUnit.NANOSECONDS);
        return res;
    }

    /**
     * Creates a new HTTP response of the specified {@code statusCode}.
     * <br/>
     * 创建一个HttpResponse，根据传入的状态码
     *
     * @throws IllegalArgumentException if the {@link HttpStatusClass} is
     *                                  {@linkplain HttpStatusClass#INFORMATIONAL informational} (1xx).
     */
    static HttpResponse of(int statusCode) {
        return of(HttpStatus.valueOf(statusCode));
    }

    /**
     * Creates a new HTTP response of the specified {@link HttpStatus}.
     * <br/>
     * 创建一个HttpResponse，根据传入的{@link HttpStatus}.
     * @throws IllegalArgumentException if the {@link HttpStatusClass} is
     *                                  {@linkplain HttpStatusClass#INFORMATIONAL informational} (1xx).
     */
    static HttpResponse of(HttpStatus status) {
        requireNonNull(status, "status");
        checkArgument(status.codeClass() != HttpStatusClass.INFORMATIONAL,
                      "status: %s (expected: a non-1xx status");

        if (isContentAlwaysEmpty(status)) {
            return new OneElementFixedHttpResponse(ResponseHeaders.of(status));
        } else {
            return of(status, MediaType.PLAIN_TEXT_UTF_8, status.toHttpData());
        }
    }

    /**
     * Creates a new HTTP response of the specified {@link HttpStatus}.
     * <br/>
     * 创建一个HttpResponse，根据传入的{@link HttpStatus}, {@link MediaType}, {@link CharSequence}
     * @param mediaType the {@link MediaType} of the response content   响应的mime类型
     * @param content the content of the response   响应的具体内容
     */
    static HttpResponse of(HttpStatus status, MediaType mediaType, CharSequence content) {
        requireNonNull(mediaType, "mediaType");
        requireNonNull(content, "content");
        return of(status, mediaType,
                  HttpData.of(mediaType.charset().orElse(StandardCharsets.UTF_8), content));
    }

    /**
     * Creates a new HTTP response of the specified {@link HttpStatus}.
     * <br/>
     * 创建一个HttpResponse，根据传入的{@link HttpStatus}, {@link MediaType}, content
     *
     * @param mediaType the {@link MediaType} of the response content   响应的mime类型
     * @param content the content of the response   响应的具体内容
     */
    static HttpResponse of(HttpStatus status, MediaType mediaType, String content) {
        requireNonNull(mediaType, "mediaType");
        requireNonNull(content, "content");
        return of(status, mediaType,
                  HttpData.of(mediaType.charset().orElse(StandardCharsets.UTF_8), content));
    }

    /**
     * Creates a new HTTP response of OK status with the content as UTF_8.
     * <br/>
     * 创建一个编码是UTF-8且成功的HttpResponse，根据传入的响应内容
     * @param content the content of the response
     */
    static HttpResponse of(String content) {
        return of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, content);
    }

    /**
     * Creates a new HTTP response of OK status with the content as UTF_8.
     * The content of the response is formatted by {@link String#format(Locale, String, Object...)} with
     * {@linkplain Locale#ENGLISH English locale}.
     *  <br/>
     *  创建一个编码是UTF-8且成功的HttpResponse，根据传入的格式化模板和参数
     *
     * @param format {@linkplain Formatter the format string} of the response content
     * @param args the arguments referenced by the format specifiers in the format string
     */
    static HttpResponse of(String format, Object... args) {
        return of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, format, args);
    }

    /**
     * Creates a new HTTP response of OK status with the content.
     *  <br/>
     *  创建一个成功的HttpResponse，根据传入的响应内容
     * @param mediaType the {@link MediaType} of the response content
     * @param content the content of the response
     */
    static HttpResponse of(MediaType mediaType, String content) {
        return of(HttpStatus.OK, mediaType, content);
    }

    /**
     * Creates a new HTTP response of OK status with the content.
     * The content of the response is formatted by {@link String#format(Locale, String, Object...)} with
     * {@linkplain Locale#ENGLISH English locale}.
     * <br/>
     * 创建一个成功的HttpResponse，根据传入的响应内容
     *
     * @param mediaType the {@link MediaType} of the response content
     * @param format {@linkplain Formatter the format string} of the response content
     * @param args the arguments referenced by the format specifiers in the format string
     */
    static HttpResponse of(MediaType mediaType, String format, Object... args) {
        return of(HttpStatus.OK, mediaType, format, args);
    }

    /**
     * Creates a new HTTP response of the specified {@link HttpStatus}.
     * The content of the response is formatted by {@link String#format(Locale, String, Object...)} with
     * {@linkplain Locale#ENGLISH English locale}.
     * <br/>
     * 创建一个HttpResponse，根据传入的状态码，mime类型，和格式化模板以及响应参数
     * @param mediaType the {@link MediaType} of the response content
     * @param format {@linkplain Formatter the format string} of the response content
     * @param args the arguments referenced by the format specifiers in the format string
     */
    static HttpResponse of(HttpStatus status, MediaType mediaType, String format, Object... args) {
        requireNonNull(mediaType, "mediaType");
        return of(status, mediaType,
                  HttpData.of(mediaType.charset().orElse(StandardCharsets.UTF_8), format, args));
    }

    /**
     * Creates a new HTTP response of the specified {@link HttpStatus}. The {@code content} will be wrapped
     * using {@link HttpData#wrap(byte[])}, so any changes made to {@code content} will be reflected in the
     * response.
     * <br/>
     * 创建一个HttpResponse，根据传入的状态码，mime类型，和响应的字节流
     *
     * @param mediaType the {@link MediaType} of the response content
     * @param content the content of the response
     */
    static HttpResponse of(HttpStatus status, MediaType mediaType, byte[] content) {
        requireNonNull(content, "content");
        return of(status, mediaType, HttpData.wrap(content));
    }

    /**
     * Creates a new HTTP response of the specified {@link HttpStatus}.
     * <br/>
     * 创建一个HttpResponse，根据传入的状态码，mime类型，和已封装的{@link HttpData}
     * @param mediaType the {@link MediaType} of the response content
     * @param content the content of the response
     */
    static HttpResponse of(HttpStatus status, MediaType mediaType, HttpData content) {
        return of(status, mediaType, content, HttpHeaders.of());
    }

    /**
     * Creates a new HTTP response of the specified {@link HttpStatus}.
     * <br/>
     * 创建一个HttpResponse，根据传入的状态码，mime类型，和已封装的{@link HttpData}，以及HttpHeaders的预告片
     *
     * @param mediaType the {@link MediaType} of the response content
     * @param content the content of the response
     * @param trailers the HTTP trailers
     */
    static HttpResponse of(HttpStatus status, MediaType mediaType, HttpData content,
                           HttpHeaders trailers) {
        requireNonNull(status, "status");
        requireNonNull(mediaType, "mediaType");
        requireNonNull(content, "content");

        final ResponseHeaders headers = ResponseHeaders.of(status,
                                                           HttpHeaderNames.CONTENT_TYPE, mediaType);
        return of(headers, content, trailers);
    }

    /**
     * Creates a new HTTP response of the specified headers.
     * <br/>
     * 创建一个HttpResponse， 根据传入的{@link ResponseHeaders}
     */
    static HttpResponse of(ResponseHeaders headers) {
        return of(headers, HttpData.EMPTY_DATA);
    }

    /**
     * Creates a new HTTP response of the specified headers and content.
     * <br/>
     * 创建一个HttpResponse， 根据传入的{@link ResponseHeaders}和{@link HttpData}
     */
    static HttpResponse of(ResponseHeaders headers, HttpData content) {
        return of(headers, content, HttpHeaders.of());
    }

    /**
     * Creates a new HTTP response of the specified objects.
     * <br/>
     * 创建一个HttpResponse， 根据传入的{@link ResponseHeaders}和{@link HttpData}、{@link HttpHeaders}
     */
    static HttpResponse of(ResponseHeaders headers, HttpData content, HttpHeaders trailers) {
        requireNonNull(headers, "headers");
        requireNonNull(content, "content");
        requireNonNull(trailers, "trailers");

        final ResponseHeaders newHeaders = setOrRemoveContentLength(headers, content, trailers);
        if (content.isEmpty() && trailers.isEmpty()) {
            ReferenceCountUtil.safeRelease(content);
            return new OneElementFixedHttpResponse(newHeaders);
        }

        if (!content.isEmpty()) {
            if (trailers.isEmpty()) {
                return new TwoElementFixedHttpResponse(newHeaders, content);
            } else {
                return new RegularFixedHttpResponse(newHeaders, content, trailers);
            }
        }

        return new TwoElementFixedHttpResponse(newHeaders, trailers);
    }

    /**
     * Creates a new HTTP response of the specified objects.
     * <br/>
     * 创建一个HttpResponse， 根据传入的{@link HttpObject}objs
     */
    static HttpResponse of(HttpObject... objs) {
        return new RegularFixedHttpResponse(objs);
    }

    /**
     * Converts the {@link AggregatedHttpResponse} into a new complete {@link HttpResponse}.
     */
    static HttpResponse of(AggregatedHttpResponse res) {
        requireNonNull(res, "res");

        final List<ResponseHeaders> informationals = res.informationals();
        final ResponseHeaders headers = res.headers();
        final HttpData content = res.content();
        final HttpHeaders trailers = res.trailers();

        if (informationals.isEmpty()) {
            return of(headers, content, trailers);
        }

        final int numObjects = informationals.size() +
                               1 /* headers */ +
                               (!content.isEmpty() ? 1 : 0) +
                               (!trailers.isEmpty() ? 1 : 0);
        final HttpObject[] objs = new HttpObject[numObjects];
        int writerIndex = 0;
        for (ResponseHeaders informational : informationals) {
            objs[writerIndex++] = informational;
        }
        objs[writerIndex++] = headers;
        if (!content.isEmpty()) {
            objs[writerIndex++] = content;
        }
        if (!trailers.isEmpty()) {
            objs[writerIndex] = trailers;
        }
        return new RegularFixedHttpResponse(objs);
    }

    /**
     * Creates a new HTTP response whose stream is produced from an existing {@link Publisher}.
     * <br/>
     * 创建一个HttpResponse， 根据传入的{@link Publisher}
     */
    static HttpResponse of(Publisher<? extends HttpObject> publisher) {
        return new PublisherBasedHttpResponse(publisher);
    }

    /**
     * Creates a new failed HTTP response.
     * <br/>
     * 创建一个失败的HttpResponse， 根据传入的异常
     */
    static HttpResponse ofFailure(Throwable cause) {
        final HttpResponseWriter res = streaming();
        res.close(cause);
        return res;
    }

    /**
     * Creates a new failed HTTP response.
     * <br/>
     * 创建一个失败的HttpResponse， 根据传入的异常
     *
     * @deprecated Use {@link #ofFailure(Throwable)}.
     */
    @Deprecated
    static HttpResponse ofFailed(Throwable cause) {
        return ofFailure(cause);
    }

    @Override
    default CompletableFuture<Void> closeFuture() {
        return completionFuture();
    }

    @Override
    CompletableFuture<Void> completionFuture();

    /**
     * Aggregates this response. The returned {@link CompletableFuture} will be notified when the content and
     * the trailers of the response are received fully.
     * <br/>
     * 整合响应结果， 当content和响应内容全部接受完毕以后，将会唤醒{@link CompletableFuture}future对象
     */
    default CompletableFuture<AggregatedHttpResponse> aggregate() {
        final CompletableFuture<AggregatedHttpResponse> future = new CompletableFuture<>();
        final HttpResponseAggregator aggregator = new HttpResponseAggregator(future, null);
        // 开始向订阅者发送报文数据
        subscribe(aggregator);
        return future;
    }

    /**
     * Aggregates this response. The returned {@link CompletableFuture} will be notified when the content and
     * the trailers of the response are received fully.
     * <br/>
     * 整合响应结果， 当content和响应内容全部接受完毕以后，将会唤醒{@link CompletableFuture}future对象
     */
    default CompletableFuture<AggregatedHttpResponse> aggregate(EventExecutor executor) {
        final CompletableFuture<AggregatedHttpResponse> future = new CompletableFuture<>();
        final HttpResponseAggregator aggregator = new HttpResponseAggregator(future, null);
        // 开始向订阅者发送报文数据
        subscribe(aggregator, executor);
        return future;
    }

    /**
     * Aggregates this response. The returned {@link CompletableFuture} will be notified when the content and
     * the trailers of the response are received fully. {@link AggregatedHttpResponse#content()} will
     * return a pooled object, and the caller must ensure to release it. If you don't know what this means,
     * use {@link #aggregate()}.
     * <br/>
     * 整合响应结果， 当content和响应内容全部接受完毕以后，将会唤醒{@link CompletableFuture}future对象。
     * {@link AggregatedHttpResponse#content()}将会返回一个池化的对象，与此同时，调用者必需释放它，如果你不知道如何使用，则尽可能地用{@link #aggregate()}.
     */
    default CompletableFuture<AggregatedHttpResponse> aggregateWithPooledObjects(ByteBufAllocator alloc) {
        requireNonNull(alloc, "alloc");
        final CompletableFuture<AggregatedHttpResponse> future = new CompletableFuture<>();
        final HttpResponseAggregator aggregator = new HttpResponseAggregator(future, alloc);
        subscribe(aggregator, SubscriptionOption.WITH_POOLED_OBJECTS);
        return future;
    }

    /**
     * Aggregates this response. The returned {@link CompletableFuture} will be notified when the content and
     * the trailers of the request is received fully. {@link AggregatedHttpResponse#content()} will
     * return a pooled object, and the caller must ensure to release it. If you don't know what this means,
     * use {@link #aggregate()}.
     * <br/>
     *
     * 整合响应结果， 当content和响应内容全部接受完毕以后，将会唤醒{@link CompletableFuture}future对象。
     * {@link AggregatedHttpResponse#content()}将会返回一个池化的对象，与此同时，调用者必需释放它，如果你不知道如何使用，则尽可能地用{@link #aggregate()}.
     */
    default CompletableFuture<AggregatedHttpResponse> aggregateWithPooledObjects(
            EventExecutor executor, ByteBufAllocator alloc) {
        requireNonNull(executor, "executor");
        requireNonNull(alloc, "alloc");
        final CompletableFuture<AggregatedHttpResponse> future = new CompletableFuture<>();
        final HttpResponseAggregator aggregator = new HttpResponseAggregator(future, alloc);
        subscribe(aggregator, executor, SubscriptionOption.WITH_POOLED_OBJECTS);
        return future;
    }
}
