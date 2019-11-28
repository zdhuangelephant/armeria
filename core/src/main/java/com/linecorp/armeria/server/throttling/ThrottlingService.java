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

import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingService;

/**
 * Decorates a {@link Service} to throttle incoming requests.
 * <br/>
 * 一个专门"掐断"请求的服务
 *
 */
public abstract class ThrottlingService<I extends Request, O extends Response>
        extends SimpleDecoratingService<I, O> {

    // 掐断策略的具体引用
    private final ThrottlingStrategy<I> strategy;
    // 响应结果转换器的引用
    private final Function<CompletionStage<? extends O>, O> responseConverter;

    /**
     * Creates a new instance that decorates the specified {@link Service}.
     * <br/>
     * 将传入的Service实例装饰成一个"装饰服务"
     */
    protected ThrottlingService(Service<I, O> delegate, ThrottlingStrategy<I> strategy,
                                Function<CompletionStage<? extends O>, O> responseConverter) {
        super(delegate);
        this.strategy = requireNonNull(strategy, "strategy");
        this.responseConverter = requireNonNull(responseConverter);
    }

    /**
     * Invoked when {@code req} is not throttled. By default, this method delegates the specified {@code req} to
     * the {@link #delegate()} of this service.
     * <br/>
     * 当req对象没有被"掐死"的时候，会调用此方法
     */
    protected O onSuccess(ServiceRequestContext ctx, I req) throws Exception {
        return delegate().serve(ctx, req);
    }

    /**
     * Invoked when {@code req} is throttled. By default, this method responds with the
     * {@link HttpStatus#SERVICE_UNAVAILABLE} status.
     * <br/>
     * 当req对象被"掐死"的时候， 会调用此方法
     */
    protected abstract O onFailure(ServiceRequestContext ctx, I req, @Nullable Throwable cause)
            throws Exception;

    @Override
    public O serve(ServiceRequestContext ctx, I req) throws Exception {
        return responseConverter.apply(
                strategy.accept(ctx, req).handleAsync((accept, thrown) -> {
                    try {
                        if (thrown != null || !accept) {
                            return onFailure(ctx, req, thrown);
                        }
                        return onSuccess(ctx, req);
                    } catch (Exception e) {
                        return Exceptions.throwUnsafely(e);
                    }
                }, ctx.contextAwareEventLoop()));
    }
}
