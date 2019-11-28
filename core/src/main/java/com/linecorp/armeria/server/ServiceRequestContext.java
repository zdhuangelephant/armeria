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

package com.linecorp.armeria.server;

import static com.google.common.base.Preconditions.checkState;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.slf4j.Logger;

import com.linecorp.armeria.common.ContentTooLargeException;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.server.logging.AccessLogWriter;

/**
 * Provides information about an invocation and related utilities. Every request being handled has its own
 * {@link ServiceRequestContext} instance.
 *
 * <br/>
 * NOTE: 提供一些调用入口、一些相关工具。每一个请求都有独立的{@link ServiceRequestContext}实例与之关联
 */
public interface ServiceRequestContext extends RequestContext {

    /**
     * Returns the server-side context of the {@link Request} that is being handled in the current thread.
     *
     * <br/>
     * NOTE: 返回即将要被当前线程处理的{@link Request}所对应的服务端的Context对象[即ServiceRequestContext]
     *
     * @throws IllegalStateException if the context is unavailable in the current thread or
     *                               the current context is not a {@link ServiceRequestContext}.
     */
    static ServiceRequestContext current() {
        final RequestContext ctx = RequestContext.current();
        checkState(ctx instanceof ServiceRequestContext,
                   "The current context is not a server-side context: %s", ctx);
        return (ServiceRequestContext) ctx;
    }

    /**
     * Returns the server-side context of the {@link Request} that is being handled in the current thread.
     *
     * @return the {@link ServiceRequestContext} available in the current thread,
     *         or {@code null} if unavailable.
     * @throws IllegalStateException if the current context is not a {@link ServiceRequestContext}.
     */
    @Nullable
    static ServiceRequestContext currentOrNull() {
        final RequestContext ctx = RequestContext.currentOrNull();
        if (ctx == null) {
            return null;
        }
        checkState(ctx instanceof ServiceRequestContext,
                   "The current context is not a server-side context: %s", ctx);
        return (ServiceRequestContext) ctx;
    }

    /**
     * Maps the server-side context of the {@link Request} that is being handled in the current thread.
     *
     * @param mapper the {@link Function} that maps the {@link ServiceRequestContext}
     * @param defaultValueSupplier the {@link Supplier} that provides the value when the context is unavailable
     *                             in the current thread. If {@code null}, the {@code null} will be returned
     *                             when the context is unavailable in the current thread.
     * @throws IllegalStateException if the current context is not a {@link ServiceRequestContext}.
     */
    @Nullable
    static <T> T mapCurrent(
            Function<? super ServiceRequestContext, T> mapper, @Nullable Supplier<T> defaultValueSupplier) {

        final ServiceRequestContext ctx = currentOrNull();
        if (ctx != null) {
            return mapper.apply(ctx);
        }

        if (defaultValueSupplier != null) {
            return defaultValueSupplier.get();
        }

        return null;
    }

    /**
     * Returns a new {@link ServiceRequestContext} created from the specified {@link HttpRequest}.
     * Note that it is not usually required to create a new context by yourself, because Armeria
     * will always provide a context object for you. However, it may be useful in some cases such as
     * unit testing.
     *
     * <br/>
     * NOTE: 创建一个指定的{@link ServiceRequestContext}从某个特定的{@link HttpRequest}，一般我们是不需要自己创建上下文对象的。但在单元测试中我们可能会用得到
     *
     * @see ServiceRequestContextBuilder
     */
    static ServiceRequestContext of(HttpRequest request) {
        return ServiceRequestContextBuilder.of(request).build();
    }

    /**
     * Returns the {@link HttpRequest} associated with this context.
     */
    @Override
    @SuppressWarnings("unchecked")
    HttpRequest request();

    /**
     * Returns the remote address of this request.
     */
    @Nonnull
    @Override
    <A extends SocketAddress> A remoteAddress();

    /**
     * Returns the local address of this request.
     */
    @Nonnull
    @Override
    <A extends SocketAddress> A localAddress();

    /**
     * Returns the address of the client who initiated this request.
     */
    default InetAddress clientAddress() {
        final InetSocketAddress remoteAddress = remoteAddress();
        return remoteAddress.getAddress();
    }

    @Override
    ServiceRequestContext newDerivedContext();

    @Override
    ServiceRequestContext newDerivedContext(Request request);

    /**
     * Returns the {@link Server} that is handling the current {@link Request}.
     * <br/>
     * NOTE: 返回正在处理当前请求的Server引用
     */
    Server server();

    /**
     * Returns the {@link VirtualHost} that is handling the current {@link Request}.
     * <br/>
     * NOTE: 返回正在处理当前请求的{@link VirtualHost}引用
     */
    VirtualHost virtualHost();

    /**
     * Returns the {@link Route} associated with the {@link Service} that is handling the current
     * {@link Request}.
     * <br/>
     * NOTE: 返回正在处理当前{@link Request}的某个具体{@link Service}对象相关联的{@link Route}引用
     */
    Route route();

    /**
     * Returns the {@link RoutingContext} used to find the {@link Service}.
     * <br/>
     * NOTE: 返回可寻找{@link Service}的{@link RoutingContext}引用
     */
    RoutingContext routingContext();

    /**
     * Returns the path parameters mapped by the {@link #route()} associated with the {@link Service}
     * that is handling the current {@link Request}.
     * <br/>
     * NOTE: 返回处理当前{@link Request}的某个{@link Service}关联的{@link #route()}所获取的参数集合
     */
    Map<String, String> pathParams();

    /**
     * Returns the value of the specified path parameter.
     * <br/>
     * NOTE：返回指定请求参数的值
     */
    @Nullable
    default String pathParam(String name) {
        return pathParams().get(name);
    }

    /**
     * Returns the {@link Service} that is handling the current {@link Request}.
     * <br/>
     * NOTE: 返回正在处理当前请求的{@link Service}
     */
    <T extends Service<HttpRequest, HttpResponse>> T service();

    /**
     * Returns the {@link ExecutorService} that could be used for executing a potentially long-running task.
     * The {@link ExecutorService} will propagate the {@link ServiceRequestContext} automatically when running
     * a task.
     *
     * <p>Note that performing a long-running task in {@link Service#serve(ServiceRequestContext, Request)}
     * may block the {@link Server}'s I/O event loop and thus should be executed in other threads.
     * <br/>
     * NOTE: 返回执行"长任务"的线程池，并在执行任务的时候回自动将当前的上下文传递到task内
     */
    ExecutorService blockingTaskExecutor();

    /**
     * Returns the {@link #path()} with its context path removed. This method can be useful for a reusable
     * service bound at various path prefixes.
     * <br/>
     * NOTE: 返回一个请求的绝对路径但是此Context的路径被删除了。这是用来多个Service重用的时候可以方便随时切换请求前缀
     */
    String mappedPath();

    /**
     * Returns the {@link #decodedPath()} with its context path removed. This method can be useful for
     * a reusable service bound at various path prefixes.
     * <br/>
     * NOTE: 返回请求的绝对路径[解码以后的]，不包含参数部分。但是Context的路径被删除了，这是用来多个Service重用的时候可以方便随时切换请求前缀
     */
    String decodedMappedPath();

    /**
     * Returns the negotiated producible media type. If the media type negotiation is not used for the
     * {@link Service}, {@code null} would be returned.
     *
     * <br/>
     * NOTE:
     */
    @Nullable
    MediaType negotiatedResponseMediaType();

    /**
     * Returns the negotiated producible media type. If the media type negotiation is not used for the
     * {@link Service}, {@code null} would be returned.
     *
     * @deprecated Use {@link #negotiatedResponseMediaType()}.
     */
    @Deprecated
    @Nullable
    default MediaType negotiatedProduceType() {
        return negotiatedResponseMediaType();
    }

    /**
     * Returns the {@link Logger} of the {@link Service}.
     *
     * @deprecated Use a logging framework integration such as {@code RequestContextExportingAppender} in
     *             {@code armeria-logback}.
     */
    @Deprecated
    Logger logger();

    /**
     * Returns the amount of time allowed until receiving the current {@link Request} and sending
     * the corresponding {@link Response} completely.
     * This value is initially set from {@link ServiceConfig#requestTimeoutMillis()}.
     *
     * <br/>
     * NOTE: 返回超时时间
     */
    long requestTimeoutMillis();

    /**
     * Sets the amount of time allowed until receiving the current {@link Request} and sending
     * the corresponding {@link Response} completely.
     * This value is initially set from {@link ServiceConfig#requestTimeoutMillis()}.
     * <br/>
     * NOTE: 设置超时时间
     */
    void setRequestTimeoutMillis(long requestTimeoutMillis);

    /**
     * Sets the amount of time allowed until receiving the current {@link Request} and sending
     * the corresponding {@link Response} completely.
     * This value is initially set from {@link ServiceConfig#requestTimeoutMillis()}.
     * <br/>
     * NOTE: 设置请求超时时间
     */
    void setRequestTimeout(Duration requestTimeout);

    /**
     * Sets a handler to run when the request times out. {@code requestTimeoutHandler} must close the response,
     * e.g., by calling {@link HttpResponseWriter#close()}. If not set, the response will be closed with
     * {@link HttpStatus#SERVICE_UNAVAILABLE}.
     *
     * <p>For example,
     * <pre>{@code
     *   HttpResponseWriter res = HttpResponse.streaming();
     *   ctx.setRequestTimeoutHandler(() -> {
     *      res.write(ResponseHeaders.of(HttpStatus.OK,
     *                                   HttpHeaderNames.CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8));
     *      res.write(HttpData.ofUtf8("Request timed out."));
     *      res.close();
     *   });
     *   ...
     * }</pre>
     *
     * <br/>
     * NOTE: 当请求超时以后，需要我们设置一个超时处理器，来告诉客户端请求超时。如example所示
     */
    void setRequestTimeoutHandler(Runnable requestTimeoutHandler);

    /**
     * Returns whether this {@link ServiceRequestContext} has been timed-out (e.g., when the
     * corresponding request passes a deadline).
     *
     * <br/>
     * NOTE: 判断当前请求是否超时
     */
    @Override
    boolean isTimedOut();

    /**
     * Returns the maximum length of the current {@link Request}.
     * This value is initially set from {@link ServiceConfig#maxRequestLength()}.
     * If 0, there is no limit on the request size.
     *
     * @see ContentTooLargeException
     *
     * <br/>
     * 返回Request的payload是多少，如果返回的0，则说明请求负载体的大小是无限制的。否则就是返回值就是阈值
     */
    long maxRequestLength();

    /**
     * Sets the maximum length of the current {@link Request}.
     * This value is initially set from {@link ServiceConfig#maxRequestLength()}.
     * If 0, there is no limit on the request size.
     *
     * @see ContentTooLargeException
     *
     * <br/>
     * NOTE: 设置Request的payload的上限阈值
     */
    void setMaxRequestLength(long maxRequestLength);

    /**
     * Returns whether the verbose response mode is enabled. When enabled, the service responses will contain
     * the exception type and its full stack trace, which may be useful for debugging while potentially
     * insecure. When disabled, the service responses will not expose such server-side details to the client.
     * <br/>
     * 返回是否开启full-stack信息的响应载体。如果开启则会返回debug的详细信息，方便调试。默认是关闭的
     */
    boolean verboseResponses();

    AccessLogWriter accessLogWriter();

    /**
     * Returns an immutable {@link HttpHeaders} which is included when a {@link Service} sends an
     * {@link HttpResponse}.
     * <br/>
     * 返回一个不可变的{@link HttpHeaders}
     */
    HttpHeaders additionalResponseHeaders();

    /**
     * Sets a header with the specified {@code name} and {@code value}. This will remove all previous values
     * associated with the specified {@code name}.
     * The header will be included when a {@link Service} sends an {@link HttpResponse}.
     * <br/>
     * NOTE: 由传入的name/value，来设置Header头
     */
    void setAdditionalResponseHeader(CharSequence name, Object value);

    /**
     * Clears the current header and sets the specified {@link HttpHeaders} which is included when a
     * {@link Service} sends an {@link HttpResponse}.
     *
     * <br/>
     * NOTE: 清除当前头部信息，并且将当前的响应体内的头部信息，重新设置为其响应头
     */
    void setAdditionalResponseHeaders(Iterable<? extends Entry<? extends CharSequence, ?>> headers);

    /**
     * Adds a header with the specified {@code name} and {@code value}. The header will be included when
     * a {@link Service} sends an {@link HttpResponse}.
     * <br/>
     * NOTE: 设置响应头
     */
    void addAdditionalResponseHeader(CharSequence name, Object value);

    /**
     * Adds the specified {@link HttpHeaders} which is included when a {@link Service} sends an
     * {@link HttpResponse}.
     * <br/>
     * NOTE: 设置一个响应头
     */
    void addAdditionalResponseHeaders(Iterable<? extends Entry<? extends CharSequence, ?>> headers);

    /**
     * Removes all headers with the specified {@code name}.
     *
     * @return {@code true} if at least one entry has been removed
     * <br/>
     * NOTE: 删除某个响应头
     */
    boolean removeAdditionalResponseHeader(CharSequence name);

    /**
     * Returns the {@link HttpHeaders} which is returned along with any other trailers when a
     * {@link Service} completes an {@link HttpResponse}.
     * <br/>
     * NOTE: 返回额外的头部信息[即当响应被处理完毕]
     */
    HttpHeaders additionalResponseTrailers();

    /**
     * Sets a trailer with the specified {@code name} and {@code value}. This will remove all previous values
     * associated with the specified {@code name}.
     * The trailer will be included when a {@link Service} completes an {@link HttpResponse}.
     *
     * <br/>
     * NOTE: 根据k/v设置追踪者，当k已存在时，旧value会被覆盖
     */
    void setAdditionalResponseTrailer(CharSequence name, Object value);

    /**
     * Clears the current trailer and sets the specified {@link HttpHeaders} which is included when a
     * {@link Service} completes an {@link HttpResponse}.
     * <br/>
     * NOTE: 清除当前追踪者的数据，并且通过参数重新设置Header
     */
    void setAdditionalResponseTrailers(Iterable<? extends Entry<? extends CharSequence, ?>> headers);

    /**
     * Adds a trailer with the specified {@code name} and {@code value}. The trailer will be included when
     * a {@link Service} completes an {@link HttpResponse}.
     * <br/>
     * NOTE: 通过指定的键值对重新添加一个追踪者
     */
    void addAdditionalResponseTrailer(CharSequence name, Object value);

    /**
     * Adds the specified {@link HttpHeaders} which is included when a {@link Service} completes an
     * {@link HttpResponse}.
     * <br/>
     * 添加指定的头部信息
     */
    void addAdditionalResponseTrailers(Iterable<? extends Entry<? extends CharSequence, ?>> headers);

    /**
     * Removes all trailers with the specified {@code name}.
     *
     * @return {@code true} if at least one entry has been removed
     * <br/>
     * NOTE：通过指定的key删除响应的追踪者[可能删除的是多个]
     */
    boolean removeAdditionalResponseTrailer(CharSequence name);

    /**
     * Returns the proxied addresses if the current {@link Request} is received through a proxy.
     * <br/>
     * NOTE：返回当前请求的被代理的地址
     */
    @Nullable
    ProxiedAddresses proxiedAddresses();
}
