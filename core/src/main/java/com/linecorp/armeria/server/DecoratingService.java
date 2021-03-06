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

import static java.util.Objects.requireNonNull;

import java.util.Optional;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;

/**
 *
 * <br/>
 * A {@link Service} that decorates another {@link Service}. Use {@link SimpleDecoratingService} or
 * {@link Service#decorate(DecoratingServiceFunction)} if your {@link Service} has the same {@link Request}
 * and {@link Response} type with the {@link Service} being decorated.
 *
 * @param <T_I> the {@link Request} type of the {@link Service} being decorated     要被装饰Service的Request类型
 * @param <T_O> the {@link Response} type of the {@link Service} being decorated    要被装饰Service的Response类型
 * @param <R_I> the {@link Request} type of this {@link Service}
 * @param <R_O> the {@link Response} type of this {@link Service}
 *
 * <pre>{@code
 * 将RpcService转化为HttpService
 * // Transforms an RpcService into an HttpService.
 * public class MyRpcService extends DecoratingService<RpcRequest, RpcResponse,
 *                                                     HttpRequest, HttpResponse> {
 *
 *     public MyRpcService(Service<? super RpcRequest, ? extends RpcResponse> delegate) {
 *         super(delegate);
 *     }
 *
 *     @Override
 *     public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
 *         // This method has been greatly simplified for easier understanding.
 *         // In reality, we will have to do this asynchronously.
 *         RpcRequest rpcReq = convertToRpcRequest(req);
 *         RpcResponse rpcRes = delegate().serve(ctx, rpcReq);
 *         return convertToHttpResponse(rpcRes);
 *     }
 *
 *     private RpcRequest convertToRpcRequest(HttpRequest req) { ... }
 *     private HttpResponse convertToHttpResponse(RpcResponse res) { ... }
 * }
 * }</pre>
 */
public abstract class DecoratingService<T_I extends Request, T_O extends Response,
                                        R_I extends Request, R_O extends Response>
        implements Service<R_I, R_O> {

    // eg: A.decorate(B.class).decorate(C.class) 那么delegate引用的变化时序为： null -> A -> B
    // 要被装饰的Service
    private final Service<T_I, T_O> delegate;

    /**
     * Creates a new instance that decorates the specified {@link Service}.
     */
    protected DecoratingService(Service<T_I, T_O> delegate) {
        this.delegate = requireNonNull(delegate, "delegate");
    }

    /**
     * Returns the {@link Service} being decorated.
     */
    @SuppressWarnings("unchecked")
    protected final <T extends Service<T_I, T_O>> T delegate() {
        return (T) delegate;
    }

    @Override
    public void serviceAdded(ServiceConfig cfg) throws Exception {
        ServiceCallbackInvoker.invokeServiceAdded(cfg, delegate);
    }

    @Override
    public final <T> Optional<T> as(Class<T> serviceType) {
        final Optional<T> result = Service.super.as(serviceType);
        return result.isPresent() ? result : delegate.as(serviceType);
    }

    @Override
    public boolean shouldCachePath(String path, @Nullable String query, Route route) {
        return delegate.shouldCachePath(path, query, route);
    }

    @Override
    public String toString() {
        final String simpleName = getClass().getSimpleName();
        final String name = simpleName.isEmpty() ? getClass().getName() : simpleName;
        return name + '(' + delegate + ')';
    }
}
