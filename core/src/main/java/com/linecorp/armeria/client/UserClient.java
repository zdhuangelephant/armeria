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

import static com.linecorp.armeria.internal.ClientUtil.initContextAndExecuteWithFallback;

import java.net.URI;
import java.util.function.BiFunction;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLogAvailability;
import com.linecorp.armeria.common.util.ReleasableHolder;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.EventLoop;

/**
 * A base class for implementing a user's entry point for sending a {@link Request}. 为了方便用户切入进来(区别于{@link Client})，从而发送Request请求的一个基类
 *
 * <p>It provides the utility methods for easily forwarding a {@link Request} from a user to a {@link Client}. 该类提供了方便的工具方法使请求从用户端过渡到{@link Client}
 *
 * <p>Note that this class is not a subtype of {@link Client}, although its name may mislead. 虽然名字容易让人误会，但这个类并不是{@link Client}的子类！
 *
 * <p>UserClient只有一个具体的实现子类，那就是{@link DefaultHttpClient}，不算上thrift内的话
 *
 * @param <I> the request type
 * @param <O> the response type
 */
public abstract class UserClient<I extends Request, O extends Response> implements ClientBuilderParams {
    // 构建参数
    private final ClientBuilderParams params;
    // 将请求从用户端真正转发到实际干活的Client端
    private final Client<I, O> delegate;
    // 监控中心
    private final MeterRegistry meterRegistry;
    // 协议支持
    private final SessionProtocol sessionProtocol;
    // Endpoint声明
    private final Endpoint endpoint;

    /**
     * Creates a new instance.
     *
     * @param params the parameters used for constructing the client
     * @param delegate the {@link Client} that will process {@link Request}s
     * @param meterRegistry the {@link MeterRegistry} that collects various stats
     * @param sessionProtocol the {@link SessionProtocol} of the {@link Client}
     * @param endpoint the {@link Endpoint} of the {@link Client}
     */
    protected UserClient(ClientBuilderParams params, Client<I, O> delegate, MeterRegistry meterRegistry,
                         SessionProtocol sessionProtocol, Endpoint endpoint) {
        this.params = params;
        this.delegate = delegate;
        this.meterRegistry = meterRegistry;
        this.sessionProtocol = sessionProtocol;
        this.endpoint = endpoint;
    }

    @Override
    public ClientFactory factory() {
        return params.factory();
    }

    @Override
    public URI uri() {
        return params.uri();
    }

    @Override
    public Class<?> clientType() {
        return params.clientType();
    }

    @Override
    public final ClientOptions options() {
        return params.options();
    }

    /**
     * Returns the {@link Client} that will process {@link Request}s.
     */
    @SuppressWarnings("unchecked")
    protected final <U extends Client<I, O>> U delegate() {
        return (U) delegate;
    }

    /**
     * Returns the {@link SessionProtocol} of the {@link #delegate()}.
     */
    protected final SessionProtocol sessionProtocol() {
        return sessionProtocol;
    }

    /**
     * Returns the {@link Endpoint} of the {@link #delegate()}.
     */
    protected final Endpoint endpoint() {
        return endpoint;
    }

    /**
     * Executes the specified {@link Request} via {@link #delegate()}.
     *
     * @param method the method of the {@link Request}
     * @param path the path part of the {@link Request} URI
     * @param query the query part of the {@link Request} URI
     * @param fragment the fragment part of the {@link Request} URI
     * @param req the {@link Request}
     * @param fallback the fallback response {@link BiFunction} to use when
     *                 {@link Client#execute(ClientRequestContext, Request)} of {@link #delegate()} throws
     *                 an exception instead of returning an error response
     */
    protected final O execute(HttpMethod method, String path, @Nullable String query, @Nullable String fragment,
                              I req, BiFunction<ClientRequestContext, Throwable, O> fallback) {
        return execute(null, endpoint, method, path, query, fragment, req, fallback);
    }

    /**
     * Executes the specified {@link Request} via {@link #delegate()}. 经过{@link #delegate()}方法执行指定的Request
     *
     * @param eventLoop the {@link EventLoop} to execute the {@link Request}
     * @param endpoint the {@link Endpoint} of the {@link Request}
     * @param method the method of the {@link Request}
     * @param path the path part of the {@link Request} URI
     * @param query the query part of the {@link Request} URI
     * @param fragment the fragment part of the {@link Request} URI
     * @param req the {@link Request}
     * @param fallback the fallback response {@link BiFunction} to use when
     *                 {@link Client#execute(ClientRequestContext, Request)} of {@link #delegate()} throws
     */
    protected final O execute(@Nullable EventLoop eventLoop, Endpoint endpoint,
                              HttpMethod method, String path, @Nullable String query, @Nullable String fragment,
                              I req, BiFunction<ClientRequestContext, Throwable, O> fallback) {
        final DefaultClientRequestContext ctx;
        if (eventLoop == null) {
            final ReleasableHolder<EventLoop> releasableEventLoop = factory().acquireEventLoop(endpoint);
            ctx = new DefaultClientRequestContext(
                    releasableEventLoop.get(), meterRegistry, sessionProtocol,
                    method, path, query, fragment, options(), req);
            // 到了请求处理完毕的点，要把releasableEventLoop显示的释放掉。
            ctx.log().addListener(log -> releasableEventLoop.release(), RequestLogAvailability.COMPLETE);
        } else {
            ctx = new DefaultClientRequestContext(eventLoop, meterRegistry, sessionProtocol,
                                                  method, path, query, fragment, options(), req);
        }

        return initContextAndExecuteWithFallback(delegate(), ctx, endpoint, fallback);
    }
}
