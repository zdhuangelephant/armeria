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

import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nullable;
import javax.net.ssl.SSLSession;

import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.util.Attribute;
import io.netty.util.AttributeMap;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.Promise;

/**
 * Provides information about a {@link Request}, its {@link Response} and related utilities.
 * A server-side {@link Request} has a {@link ServiceRequestContext} and
 * a client-side {@link Request} has a {@link ClientRequestContext}.
 *
 *  与服务端相关的是{@link ServiceRequestContext}.
 *  与客户端相关的是{@link ClientRequestContext}.
 */
public interface RequestContext extends AttributeMap {

    /**
     * Returns the context of the {@link Request} that is being handled in the current thread.
     * <br/>
     * NOTE: 返回要被当前线程处理的{@link Request}的上下文.
     *
     * <br/>
     * NOTE: 如果上下文对象在当前线程内不可用，则抛出@throws IllegalStateException
     * @throws IllegalStateException if the context is unavailable in the current thread
     *
     */
    static <T extends RequestContext> T current() {
        final T ctx = currentOrNull();
        if (ctx == null) {
            throw new IllegalStateException(RequestContext.class.getSimpleName() + " unavailable");
        }
        return ctx;
    }

    /**
     * Returns the context of the {@link Request} that is being handled in the current thread.
     *
     * <br/>
     * NOTE: 返回要被当前线程处理的{@link Request}的上下文. 如果不可用，则不会抛出异常而直接返回null
     * @return the {@link RequestContext} available in the current thread, or {@code null} if unavailable.
     */
    @Nullable
    static <T extends RequestContext> T currentOrNull() {
        return RequestContextThreadLocal.get();
    }

    /**
     * Maps the context of the {@link Request} that is being handled in the current thread.
     * 映射到当前线程内正在处理者的请求的Client端的请求上下文。
     * @param mapper the {@link Function} that maps the {@link RequestContext}
     * @param defaultValueSupplier the {@link Supplier} that provides the value when the context is unavailable
     *                             in the current thread. If {@code null}, the {@code null} will be returned
     *                             when the context is unavailable in the current thread.
     * NOTE: 返回要被当前线程处理的{@link Request}的上下文，并提供一个lambda表达式，但是这个会提供一个默认值
     */
    @Nullable
    static <T> T mapCurrent(
            Function<? super RequestContext, T> mapper, @Nullable Supplier<T> defaultValueSupplier) {

        final RequestContext ctx = currentOrNull();
        if (ctx != null) {
            return mapper.apply(ctx);
        }

        if (defaultValueSupplier != null) {
            return defaultValueSupplier.get();
        }

        return null;
    }

    /**
     * Returns the {@link Request} associated with this context.
     * <br/>
     * NOTE: 返回与之上下文对象相关联的Request
     *
     */
    <T extends Request> T request();

    /**
     * NOTE: 用传入的Request替换掉与当前上下文相关联的Request对象, 这个方法用来装饰http的头或者rpc调用的参数时候，会用得到
     * <br/>
     * Replaces the {@link Request} associated with this context with the specified one. This method is useful
     * to a decorator that manipulates HTTP request headers or RPC call parameters.
     *
     * <p>Note that it is a bad idea to change the values of the pseudo headers ({@code ":method"},
     * {@code ":path"}, {@code ":scheme"} and {@code ":authority"}) when replacing an {@link HttpRequest},
     * because the properties of this context, such as {@link #path()}, are unaffected by such an attempt.</p>
     *
     * <br/>
     * 不允许{@link HttpRequest}替换{@link RpcRequest}
     * <p>It is not allowed to replace an {@link RpcRequest} with an {@link HttpRequest} or vice versa.
     * This method will reject such an attempt and return {@code false}.</p>
     *
     * <br/>
     * NOTE: 替换成功返回true, 否则返回false
     * @return {@code true} if the {@link Request} of this context has been replaced. {@code false} otherwise.
     *
     * @see HttpRequest#of(HttpRequest, RequestHeaders)
     */
    boolean updateRequest(Request req);

    /**
     * Returns the {@link SessionProtocol} of the current {@link Request}.
     * <br/>
     * NOTE: 返回与之Request对象相关联的{@link SessionProtocol}
     */
    SessionProtocol sessionProtocol();

    /**
     * Returns the remote address of this request, or {@code null} if the connection is not established yet.
     * <br/>
     * NOTE: 返回该请求的远程地址，如果当前连接还未曾建立的话，则返回null
     */
    @Nullable
    <A extends SocketAddress> A remoteAddress();

    /**
     * Returns the local address of this request, or {@code null} if the connection is not established yet.
     * <br/>
     * NOTE: 返回本地地址，如果当前连接还未曾建立的话，则返回null
     */
    @Nullable
    <A extends SocketAddress> A localAddress();

    /**
     * The {@link SSLSession} for this request if the connection is made over TLS, or {@code null} if
     * the connection is not established yet or the connection is not a TLS connection.
     * <br/>
     * NOTE: 如果当前连接是建立在TLS上并且连接已经建立完毕的场景下，会返回{@link SSLSession}，否则返回null
     */
    @Nullable
    SSLSession sslSession();

    /**
     * Returns the HTTP method of the current {@link Request}.
     * <br/>
     * NOTE: 返回当前Request的请求方式 eg POST/GET/...
     */
    HttpMethod method();

    /**
     * Returns the absolute path part of the current {@link Request} URI, excluding the query part,
     * as defined in <a href="https://tools.ietf.org/html/rfc3986">RFC3986</a>.
     * <br/>
     * NOTE: 返回请求的绝对路径，不包含参数部分
     */
    String path();

    /**
     * Returns the absolute path part of the current {@link Request} URI, excluding the query part,
     * decoded in UTF-8.
     * <br/>
     * NOTE: 返回请求的绝对路径[解码以后的]，不包含参数部分
     */
    String decodedPath();

    /**
     * Returns the query part of the current {@link Request} URI, without the leading {@code '?'},
     * as defined in <a href="https://tools.ietf.org/html/rfc3986">RFC3986</a>.
     * <br/>
     * NOTE: 返回请求的参数，不包括?
     */
    @Nullable
    String query();

    /**
     * Returns the {@link RequestLog} that contains the information about the current {@link Request}.
     * <br/>
     * NOTE: 返回包含当前Request信息的{@link RequestLog}
     */
    RequestLog log();

    /**
     * Returns the {@link RequestLogBuilder} that collects the information about the current {@link Request}.
     * <br/>
     * NOTE: 返回一个可以收集与当前Request相关联信息的{@link RequestLogBuilder}
     */
    RequestLogBuilder logBuilder();

    /**
     * Returns the {@link MeterRegistry} that collects various stats.
     * <br/>
     * NOTE: 返回采集统计指标额注册中心
     */
    MeterRegistry meterRegistry();

    /**
     * Returns all {@link Attribute}s set in this context.
     * <br/>
     * NOTE: 返回与该Context相关联的所有绑定属性，{@link Attribute}s
     */
    Iterator<Attribute<?>> attrs();

    /**
     * Returns the {@link Executor} that is handling the current {@link Request}.
     * <br/>
     * NOTE: 返回处理当前Request的{@link Executor}
     */
    default Executor executor() {
        // The implementation is the same as eventLoop but we expose as an Executor as well given
        // how much easier it is to write tests for an Executor (i.e.,
        // when(ctx.executor()).thenReturn(MoreExecutors.directExecutor()));
        return eventLoop();
    }

    /**
     * Returns the {@link EventLoop} that is handling the current {@link Request}.
     * 因为整体的tcp通信， 是基于netty开发，所以相对于底层通信，上层应用是可以获取到处理当前请求的EventLoop。
     * <br/>
     * NOTE: 返回正在处理该Request的{@link EventLoop}
     */
    EventLoop eventLoop();

    /**
     * Returns the {@link ByteBufAllocator} for this {@link RequestContext}. Any buffers created by this
     * {@link ByteBufAllocator} must be
     * <a href="https://netty.io/wiki/reference-counted-objects.html">reference-counted</a>. If you don't know
     * what this means, you should probably use {@code byte[]} or {@link ByteBuffer} directly instead
     * of calling this method.
     *
     * <br/>
     * NOTE: 返回与当前{@link RequestContext}相关联的{@link ByteBufAllocator}实例
     */
    default ByteBufAllocator alloc() {
        throw new UnsupportedOperationException("No ByteBufAllocator available for this RequestContext.");
    }

    /**
     * Returns an {@link Executor} that will make sure this {@link RequestContext} is set as the current
     * context before executing any callback. This should almost always be used for executing asynchronous
     * callbacks in service code to make sure features that require the {@link RequestContext} work properly.
     * Most asynchronous libraries like {@link CompletableFuture} provide methods that accept an
     * {@link Executor} to run callbacks on.
     *
     * <br/>
     * NOTE: 返回{@link Executor}并且将其设置给{@link RequestContext}内的executor，传入的executor将会执行该山下文中的所有异步操作
     */
    default Executor contextAwareExecutor() {
        // The implementation is the same as contextAwareEventLoop but we expose as an Executor as well given
        // how common it is to use only as an Executor and it becomes much easier to write tests for an
        // Executor (i.e., when(ctx.contextAwareExecutor()).thenReturn(MoreExecutors.directExecutor()));
        return contextAwareEventLoop();
    }

    /**
     * Returns an {@link EventLoop} that will make sure this {@link RequestContext} is set as the current
     * context before executing any callback.
     * <br/>
     * 在执行任何回调之前，返回的{@link EventLoop}它将会确信this{@link RequestContext}会被设置成当下的Context。
     *
     * NOTE: 返回{@link EventLoop}
     */
    default EventLoop contextAwareEventLoop() {
        return new RequestContextAwareEventLoop(this, eventLoop());
    }

    /**
     * Pushes the specified context to the thread-local stack. To pop the context from the stack, call
     * {@link SafeCloseable#close()}, which can be done using a {@code try-with-resources} block.
     *
     * @deprecated Use {@link #push()}.
     */
    @Deprecated
    static SafeCloseable push(RequestContext ctx) {
        return ctx.push(true);
    }

    /**
     * Pushes the specified context to the thread-local stack. To pop the context from the stack, call
     * {@link SafeCloseable#close()}, which can be done using a {@code try-with-resources} block.
     *
     * @deprecated Use {@link #push(boolean)}.
     */
    @Deprecated
    static SafeCloseable push(RequestContext ctx, boolean runCallbacks) {
        return ctx.push(runCallbacks);
    }

    /**
     * 将指定的Context对象压栈。为了将其出栈的话可以调用{@link SafeCloseable#close()}。如下示例:
     * <br/>
     * Pushes the specified context to the thread-local stack. To pop the context from the stack, call
     * {@link SafeCloseable#close()}, which can be done using a {@code try-with-resources} block:
     * <pre>{@code
     * try (SafeCloseable ignored = ctx.push()) { // 自动关闭
     *     ...
     * }
     * }</pre>
     *
     * <p>The callbacks added by {@link #onEnter(Consumer)} and {@link #onExit(Consumer)} will be invoked
     * when the context is pushed to and removed from the thread-local stack respectively.分别的
     * <br/>
     * 所有回调方法的添加都是通过调用{@link #onEnter(Consumer)} and {@link #onExit(Consumer)}，前者对应Context对象入栈; 后者对应着Context对象出栈。
     *
     * <p>NOTE: In case of re-entrance, the callbacks will never run.  一旦二次进入，所有的回调方法将不会再次执行
     */
    default SafeCloseable push() {
        return push(true);
    }

    /**
     * NOTE: 将指定的{@link RequestContext}压入栈顶
     *
     * <br/>
     * Pushes the specified context to the thread-local stack. To pop the context from the stack, call
     * {@link SafeCloseable#close()}, which can be done using a {@code try-with-resources} block:
     * <pre>{@code
     * try (PushHandle ignored = ctx.push(true)) {
     *     ...
     * }
     * }</pre>
     *
     * <p>NOTE: This method is only useful when it is undesirable to invoke the callbacks, such as replacing
     *          the current context with another. Prefer {@link #push()} otherwise.
     * <br/>
     * 当参数为true的时候， 会将其压入栈顶
     * NOTE: 该方法仅仅在不想调用回调方法的时候会有用， eg 用一个其他的上下文对象替换另一个上下文对象的时候
     *
     * @param runCallbacks if {@code true}, the callbacks added by {@link #onEnter(Consumer)} and
     *                     {@link #onExit(Consumer)} will be invoked when the context is pushed to and
     *                     removed from the thread-local stack respectively.
     *                     If {@code false}, no callbacks will be executed.
     *                     NOTE: In case of re-entrance, the callbacks will never run.
     */
    default SafeCloseable push(boolean runCallbacks) {
        final RequestContext oldCtx = RequestContextThreadLocal.getAndSet(this);
        if (oldCtx == this) {
            // Reentrance 二次进入
            return () -> { /* no-op */ };
        }
        // oldCtx = 3393; this = 3395
        if (runCallbacks) {
            if (oldCtx != null) {
                oldCtx.invokeOnChildCallbacks(this);
                invokeOnEnterCallbacks();
                return () -> {
                    invokeOnExitCallbacks();
                    // 每次递归退出的时候， 都会把当前线程的绑定的RouteContext置为oldCtx。
                    RequestContextThreadLocal.set(oldCtx);
                };
            } else {
                // 线程首次【即栈底元素】进来的是时候， 会走入这个地方。
                invokeOnEnterCallbacks();
                return () -> {
                    invokeOnExitCallbacks();
                    // 当栈底的元素【及最后一个元素】要退出时候，清楚堆栈数据。
                    RequestContextThreadLocal.remove(); // RequestContextThreadLocal::remove  406行的代码一样的remove，jdk8新语法。
                };
            }
        } else {
            if (oldCtx != null) {
                return () -> RequestContextThreadLocal.set(oldCtx);
            } else {
                return RequestContextThreadLocal::remove;
            }
        }
    }

    /**
     * Pushes this context to the thread-local stack if there is no current context. If there is and it is not
     * same with this context (i.e. not reentrance), this method will throw an {@link IllegalStateException}.
     * To pop the context from the stack, call {@link SafeCloseable#close()},
     * which can be done using a {@code try-with-resources} block.
     *
     * <br/>
     * NOTE: 将一个RequestContext对象压入栈顶， 如果当前线程已经绑定过RequestContext并且是和当前的RequestContext不一致[eg: 是不可重入的].
     * 如此将会抛出 {@link IllegalStateException}
     */
    default SafeCloseable pushIfAbsent() {
        final RequestContext currentRequestContext = RequestContextThreadLocal.get();
        if (currentRequestContext != null && currentRequestContext != this) {
            throw new IllegalStateException(
                    "Trying to call object wrapped with context " + this + ", but context is currently " +
                    "set to " + currentRequestContext + ". This means the callback was called from " +
                    "unexpected thread or forgetting to close previous context.");
        }
        return push();
    }

    /**
     * Returns an {@link Executor} that will execute callbacks in the given {@code executor}, making sure to
     * propagate the current {@link RequestContext} into the callback execution. It is generally preferred to
     * use {@link #contextAwareEventLoop()} to ensure the callback stays on the same thread as well.
     *
     * <br/>
     * NOTE: 类似于防止RequestContext逃逸的保护层。makeContextAware(runnable)该方法类似于netty中的inEventLoop(Thread thread)，并将匹配的线程返回，用以执行当前RequestContext内的其他的callback
     *
     */
    default Executor makeContextAware(Executor executor) {
        return runnable -> executor.execute(makeContextAware(runnable));
    }

    /**
     * Returns an {@link ExecutorService} that will execute callbacks in the given {@code executor}, making
     * sure to propagate the current {@link RequestContext} into the callback execution.
     *
     * <br/>
     * NOTE: 类似于防止RequestContext逃逸的保护层。返回{@link ExecutorService}其将会执行当前RequestContext内其余的callback
     */
    default ExecutorService makeContextAware(ExecutorService executor) {
        return new RequestContextAwareExecutorService(this, executor);
    }

    /**
     * Returns a {@link Callable} that makes sure the current {@link RequestContext} is set and then invokes
     * the input {@code callable}.
     *
     * <br/>
     * NOTE: 类似于防止RequestContext逃逸的保护层。检验当前的{@link RequestContext}是否已经设置，callable
     */
    <T> Callable<T> makeContextAware(Callable<T> callable);

    /**
     * Returns a {@link Runnable} that makes sure the current {@link RequestContext} is set and then invokes
     * the input {@code runnable}.
     *
     * <br/>
     * NOTE: 类似于防止RequestContext逃逸的保护层。检验当前的{@link RequestContext}是否已经设置，并且调用参数runnable
     */
    Runnable makeContextAware(Runnable runnable);

    /**
     * Returns a {@link Function} that makes sure the current {@link RequestContext} is set and then invokes
     * the input {@code function}.
     *
     * <br/>
     * NOTE: 类似于防止RequestContext逃逸的保护层。检验当前的{@link RequestContext}是否已经设置，并且执行function
     */
    <T, R> Function<T, R> makeContextAware(Function<T, R> function);

    /**
     * Returns a {@link BiFunction} that makes sure the current {@link RequestContext} is set and then invokes
     * the input {@code function}.
     *
     * <br/>
     * NOTE: 类似于防止RequestContext逃逸的保护层。检验当前的{@link RequestContext}是否已经设置，并且执行function
     */
    <T, U, V> BiFunction<T, U, V> makeContextAware(BiFunction<T, U, V> function);

    /**
     * Returns a {@link Consumer} that makes sure the current {@link RequestContext} is set and then invokes
     * the input {@code action}.
     *
     * <br/>
     * NOTE: 类似于防止RequestContext逃逸的保护层。检验当前的{@link RequestContext}是否已经设置，并且执行action
     */
    <T> Consumer<T> makeContextAware(Consumer<T> action);

    /**
     * Returns a {@link BiConsumer} that makes sure the current {@link RequestContext} is set and then invokes
     * the input {@code action}.
     *
     * NOTE: 类似于防止RequestContext逃逸的保护层。检验当前的{@link RequestContext}是否已经设置，并且执行action
     */
    <T, U> BiConsumer<T, U> makeContextAware(BiConsumer<T, U> action);

    /**
     * Returns a {@link FutureListener} that makes sure the current {@link RequestContext} is set and then
     * invokes the input {@code listener}.
     *
     * @deprecated Use {@link CompletableFuture} instead.
     *
     * NOTE: 类似于防止RequestContext逃逸的保护层。检验当前的{@link RequestContext}是否已经设置，并且执行listener
     */
    @Deprecated
    <T> FutureListener<T> makeContextAware(FutureListener<T> listener);

    /**
     * Returns a {@link ChannelFutureListener} that makes sure the current {@link RequestContext} is set and
     * then invokes the input {@code listener}.
     *
     * @deprecated Use {@link CompletableFuture} instead.
     */
    @Deprecated
    ChannelFutureListener makeContextAware(ChannelFutureListener listener);

    /**
     * Returns a {@link GenericFutureListener} that makes sure the current {@link RequestContext} is set and
     * then invokes the input {@code listener}. Unlike other versions of {@code makeContextAware}, this one will
     * invoke the listener with the future's result even if the context has already been timed out.
     * @deprecated Use {@link CompletableFuture} instead.
     */
    @Deprecated
    <T extends Future<?>> GenericFutureListener<T> makeContextAware(GenericFutureListener<T> listener);

    /**
     * Returns a {@link CompletionStage} that makes sure the current {@link CompletionStage} is set and
     * then invokes the input {@code stage}.
     *
     * <br/>
     * NOTE: 类似于防止RequestContext逃逸的保护层 检验当前的{@link CompletionStage}是否已经设置，并且执行stage
     */
    <T> CompletionStage<T> makeContextAware(CompletionStage<T> stage);

    /**
     * Returns a {@link CompletableFuture} that makes sure the current {@link CompletableFuture} is set and
     * then invokes the input {@code future}.
     *
     * <br/>
     * NOTE: 类似于防止RequestContext逃逸的保护层。 检验当前的{@link CompletableFuture}是否已经设置，并且执行future
     */
    default <T> CompletableFuture<T> makeContextAware(CompletableFuture<T> future) {
        return makeContextAware((CompletionStage<T>) future).toCompletableFuture();
    }

    /**
     * Returns whether this {@link RequestContext} has been timed-out (e.g., when the corresponding request
     * passes a deadline).
     * <br/>
     *
     * @deprecated Use {@link ServiceRequestContext#isTimedOut()}.
     */
    @Deprecated
    boolean isTimedOut();

    /**
     * Registers {@code callback} to be run when re-entering this {@link RequestContext}, usually when using
     * the {@link #makeContextAware} family of methods. Any thread-local state associated with this context
     * should be restored by this callback.
     *
     * <br/>
     * NOTE:
     * 注册回调函数，当再次注册进{@link RequestContext}的时候，回调函数会被执行，通常使用{@link #makeContextAware}一列列的方法。
     * 任何与当前RequestContext相关联的线程栈，都应该被参数callback，重新保存。
     *
     * @param callback a {@link Consumer} whose argument is this context
     */
    void onEnter(Consumer<? super RequestContext> callback);

    /**
     * Registers {@code callback} to be run when re-entering this {@link RequestContext}, usually when using
     * the {@link #makeContextAware} family of methods. Any thread-local state associated with this context
     * should be restored by this callback.
     *
     * @deprecated Use {@link #onEnter(Consumer)} instead.
     */
    @Deprecated
    default void onEnter(Runnable callback) {
        onEnter(ctx -> callback.run());
    }

    /**
     * Registers {@code callback} to be run when re-exiting this {@link RequestContext}, usually when using
     * the {@link #makeContextAware} family of methods. Any thread-local state associated with this context
     * should be reset by this callback.
     * <br/>
     * 注册回调函数，当再次退出RequestContext的时候，此回调函数会被执行。通常使用makeContextAware一列列的方法。
     * 任何与当前RequestContext相关联的线程栈，都应该被参数callback重置。
     * <br/>
     *
     * @param callback a {@link Consumer} whose argument is this context
     *
     */
    void onExit(Consumer<? super RequestContext> callback);

    /**
     * Registers {@code callback} to be run when re-exiting this {@link RequestContext}, usually when using
     * the {@link #makeContextAware} family of methods. Any thread-local state associated with this context
     * should be reset by this callback.
     * 注册回调函数当RequestContext再次退出线程栈的时候，通常使用{@link #makeContextAware}一系列的方法。任何与当前RequestContext相关联的线程栈，都应该被参数callback重置。
     *
     * @deprecated Use {@link #onExit(Consumer)} instead.
     */
    @Deprecated
    default void onExit(Runnable callback) {
        onExit(ctx -> callback.run());
    }

    /**
     * Registers {@code callback} to be run when this context is replaced by a child context.
     * You could use this method to inherit an attribute of this context to the child contexts or
     * register a callback to the child contexts that may be created later:
     * <pre>{@code
     * ctx.onChild((curCtx, newCtx) -> {
     *     assert ctx == curCtx && curCtx != newCtx;
     *     // Inherit the value of the 'MY_ATTR' attribute to the child context.
     *     newCtx.attr(MY_ATTR).set(curCtx.attr(MY_ATTR).get());
     *     // Add a callback to the child context.
     *     newCtx.onExit(() -> { ... });
     * });
     * }</pre>
     *
     * <br/>
     * NOTE: 可以将curCtx中的属性传递到newCtx内。并且给newCtx可以添加onExit的回调方法
     *
     * @param callback a {@link BiConsumer} whose first argument is this context and
     *                 whose second argument is the new context that replaces this context
     */
    void onChild(BiConsumer<? super RequestContext, ? super RequestContext> callback);

    /**
     * Invokes all {@link #onEnter(Consumer)} callbacks. It is discouraged to use this method directly.
     * Use {@link #makeContextAware(Runnable)} or {@link #push(boolean)} instead so that the callbacks are
     * invoked automatically.
     * <br/>
     * 调用所有的通过{@link #onEnter(Consumer)}注册进去的回调方法
     * <br/>
     * NOTE: {@link #onEnter(Consumer)}方法不推荐直接调用。而是推荐通过{@link #makeContextAware(Runnable)} or {@link #push(boolean)}
     * 的形式来间接调用
     */
    void invokeOnEnterCallbacks();

    /**
     * Invokes all {@link #onExit(Consumer)} callbacks. It is discouraged to use this method directly.
     * Use {@link #makeContextAware(Runnable)} or {@link #push(boolean)} instead so that the callbacks are
     * invoked automatically.
     * <br/>
     * NOTE: {@link #onExit(Consumer)}方法不推荐直接调用。而是推荐通过{@link #makeContextAware(Runnable)} or {@link #push(boolean)}
     * 的形式来间接调用
     */
    void invokeOnExitCallbacks();

    /**
     * Invokes all {@link #onChild(BiConsumer)} callbacks. It is discouraged to use this method directly.
     * Use {@link #makeContextAware(Runnable)} or {@link #push(boolean)} instead so that the callbacks are
     * invoked automatically.
     *
     * <br/>
     * NOTE: {@link #onChild(BiConsumer)}方法不推荐直接调用。而是推荐通过{@link #makeContextAware(Runnable)} or {@link #push(boolean)}
     * 的形式来间接调用
     */
    void invokeOnChildCallbacks(RequestContext newCtx);

    /**
     * Resolves the specified {@code promise} with the specified {@code result} so that the {@code promise} is
     * marked as 'done'. If {@code promise} is done already, this method does the following:
     * <ul>
     *   <li>Log a warning about the failure, and</li>
     *   <li>Release {@code result} if it is {@linkplain ReferenceCounted a reference-counted object},
     *       such as {@link ByteBuf} and {@link FullHttpResponse}.</li>
     * </ul>
     *
     * <br/>
     * Promise对象依然可以被设置为done，即使你没有调用如下列表所示中的任一一个。
     * Note that a {@link Promise} can be done already even if you did not call this method in the following
     * cases:
     * <ul>
     *   <li>Invocation timeout - The invocation associated with the {@link Promise} has been timed out. 即调用超时</li>
     *   <li>User error - A service implementation called any of the following methods more than once:
     *       用户级别错误:
     *          在实现的Service内不仅仅一次的调用如下方法中的任一一个
     *     <ul>
     *       <li>{@link #resolvePromise(Promise, Object)}</li>
     *       <li>{@link #rejectPromise(Promise, Throwable)}</li>
     *       <li>{@link Promise#setSuccess(Object)}</li>
     *       <li>{@link Promise#setFailure(Throwable)}</li>
     *       <li>{@link Promise#cancel(boolean)}</li>
     *     </ul>
     *   </li>
     * </ul>
     *
     * @deprecated Use {@link CompletableFuture} instead.
     */
    @Deprecated
    default void resolvePromise(Promise<?> promise, Object result) {
        @SuppressWarnings("unchecked")
        final Promise<Object> castPromise = (Promise<Object>) promise;

        if (castPromise.trySuccess(result)) {
            // Resolved successfully.
            return;
        }

        try {
            if (!(promise.cause() instanceof TimeoutException)) {
                // Log resolve failure unless it is due to a timeout.
                LoggerFactory.getLogger(RequestContext.class).warn(
                        "Failed to resolve a completed promise ({}) with {}", promise, result);
            }
        } finally {
            ReferenceCountUtil.safeRelease(result);
        }
    }

    /**
     * Rejects the specified {@code promise} with the specified {@code cause}. If {@code promise} is done
     * already, this method logs a warning about the failure. Note that a {@link Promise} can be done already
     * even if you did not call this method in the following cases:
     * <ul>
     *   <li>Invocation timeout - The invocation associated with the {@link Promise} has been timed out.</li>
     *   <li>User error - A service implementation called any of the following methods more than once:
     *     <ul>
     *       <li>{@link #resolvePromise(Promise, Object)}</li>
     *       <li>{@link #rejectPromise(Promise, Throwable)}</li>
     *       <li>{@link Promise#setSuccess(Object)}</li>
     *       <li>{@link Promise#setFailure(Throwable)}</li>
     *       <li>{@link Promise#cancel(boolean)}</li>
     *     </ul>
     *   </li>
     * </ul>
     *
     * @deprecated Use {@link CompletableFuture} instead.
     */
    @Deprecated
    default void rejectPromise(Promise<?> promise, Throwable cause) {
        if (promise.tryFailure(cause)) {
            // Fulfilled successfully.
            return;
        }

        final Throwable firstCause = promise.cause();
        if (firstCause instanceof TimeoutException) {
            // Timed out already.
            return;
        }

        if (Exceptions.isExpected(cause)) {
            // The exception that was thrown after firstCause (often a transport-layer exception)
            // was a usual expected exception, not an error.
            return;
        }

        LoggerFactory.getLogger(RequestContext.class).warn(
                "Failed to reject a completed promise ({}) with {}", promise, cause, cause);
    }

    /**
     * Creates a new {@link RequestContext} whose properties and {@link Attribute}s are copied from this
     * {@link RequestContext}, except having its own {@link RequestLog}.
     *
     * <br/>
     * NOTE: 创建一个新的{@link RequestContext}，新的RequestContext内的所有属性都是从this.RequestContext内拷贝的，除了{@link RequestLog}以外。
     */
    RequestContext newDerivedContext();

    /**
     * Creates a new {@link RequestContext} whose properties and {@link Attribute}s are copied from this
     * {@link RequestContext}, except having a different {@link Request} and its own {@link RequestLog}.
     *
     * <br/>
     * NOTE: 创建一个新的{@link RequestContext}，新的RequestContext内的所有属性都是从this.RequestContext内拷贝的，除了{@link RequestLog}和{@link Request}以外。
     */
    RequestContext newDerivedContext(Request request);
}
