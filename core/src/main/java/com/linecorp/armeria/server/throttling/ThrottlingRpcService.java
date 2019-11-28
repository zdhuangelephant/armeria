/*
 * Copyright 2017 LINE Corporation
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
package com.linecorp.armeria.server.throttling;

import static java.util.Objects.requireNonNull;

import java.util.function.Function;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Decorates an RPC {@link Service} to throttle incoming requests.
 * <br/>
 * 装饰一个RPC{@link Service}来应对即将到来的请求
 */
public class ThrottlingRpcService extends ThrottlingService<RpcRequest, RpcResponse> {
    /**
     * Creates a new decorator using the specified {@link ThrottlingStrategy} instance.
     * <br/>
     * 用传入的"掐死"策略参数，来创建一个装饰器
     * @param strategy The {@link ThrottlingStrategy} instance to be used
     */
    public static Function<Service<RpcRequest, RpcResponse>, ThrottlingRpcService>
    newDecorator(ThrottlingStrategy<RpcRequest> strategy) {
        requireNonNull(strategy, "strategy");
        return delegate -> new ThrottlingRpcService(delegate, strategy);
    }

    /**
     * Creates a new instance that decorates the specified {@link Service}.
     * <br/>
     * 创建一个新对象， 通过传入的{@link Service}和"掐死"策略
     */
    protected ThrottlingRpcService(Service<RpcRequest, RpcResponse> delegate,
                                   ThrottlingStrategy<RpcRequest> strategy) {
        super(delegate, strategy, RpcResponse::from);
    }

    /**
     * Invoked when {@code req} is throttled. By default, this method responds with a
     * {@link HttpStatusException} with {@code 503 Service Unavailable}.
     * <br/>
     * FailFast
     * <br/>
     * 当请求被掐死后，这个方法将会以{@link HttpStatusException}异常的形式抛出，并且携带{@code 503 Service Unavailable}
     *
     */
    @Override
    protected RpcResponse onFailure(ServiceRequestContext ctx, RpcRequest req, @Nullable Throwable cause)
            throws Exception {
        return RpcResponse.ofFailure(HttpStatusException.of(HttpStatus.SERVICE_UNAVAILABLE));
    }
}
