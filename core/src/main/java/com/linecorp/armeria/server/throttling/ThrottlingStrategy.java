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

import static java.util.concurrent.CompletableFuture.completedFuture;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Determines whether a request should be throttled.
 * <br/>
 * 决定一个请求是否被允许
 */
public abstract class ThrottlingStrategy<T extends Request> {
    // 全局的策略ID
    private static final AtomicInteger GLOBAL_STRATEGY_ID = new AtomicInteger();

    // 申明一个从来不使用的策略
    private static final ThrottlingStrategy<?> NEVER =
            new ThrottlingStrategy<Request>("throttling-strategy-never") {
                @Override
                public CompletionStage<Boolean> accept(ServiceRequestContext ctx, Request request) {
                    return completedFuture(false);
                }
            };

    // 声明一个一直使用的策略
    private static final ThrottlingStrategy<?> ALWAYS =
            new ThrottlingStrategy<Request>("throttling-strategy-always") {
                @Override
                public CompletionStage<Boolean> accept(ServiceRequestContext ctx, Request request) {
                    return completedFuture(true);
                }
            };

    // 该策略的名字
    private final String name;

    /**
     * Creates a new {@link ThrottlingStrategy} with a default name.
     * <br/>
     * 通过一个默认的名字创建一个新的策略
     */
    protected ThrottlingStrategy() {
        this(null);
    }

    /**
     * Creates a new {@link ThrottlingStrategy} with specified name.
     */
    protected ThrottlingStrategy(@Nullable String name) {
        if (name != null) {
            this.name = name;
        } else {
            this.name = "throttling-strategy-" +
                        (getClass().isAnonymousClass() ? Integer.toString(GLOBAL_STRATEGY_ID.getAndIncrement())
                                                       : getClass().getSimpleName());
        }
    }

    /**
     * Returns a singleton {@link ThrottlingStrategy} that never accepts requests.
     * <br/>
     * 返回一个单例的从不接受任何请求的策略
     */
    @SuppressWarnings("unchecked")
    public static <T extends Request> ThrottlingStrategy<T> never() {
        return (ThrottlingStrategy<T>) NEVER;
    }

    /**
     * Returns a singleton {@link ThrottlingStrategy} that always accepts requests.
     * <br/>
     * 返回一个单例的不受任何限制的策略
     */
    @SuppressWarnings("unchecked")
    public static <T extends Request> ThrottlingStrategy<T> always() {
        return (ThrottlingStrategy<T>) ALWAYS;
    }

    /**
     * Creates a new {@link ThrottlingStrategy} that determines whether a request should be accepted or not
     * using a given {@link BiFunction} instance.
     * <br/>
     * 创建一个新的策略， 决定一个请求是否被接受，或者传递进来一个BiFunction的实例来决定请求是否应该被允许。
     */
    public static <T extends Request> ThrottlingStrategy<T> of(
            BiFunction<ServiceRequestContext, T, CompletionStage<Boolean>> function,
            String strategyName) {
        return new ThrottlingStrategy<T>(strategyName) {
            @Override
            public CompletionStage<Boolean> accept(ServiceRequestContext ctx, T request) {
                return function.apply(ctx, request);
            }
        };
    }

    /**
     * Creates a new {@link ThrottlingStrategy} that determines whether a request should be accepted or not
     * using a given {@link BiFunction} instance.
     * <br/>
     * 创建一个新的策略， 决定一个请求是否被接受，或者传递进来一个BiFunction的实例来决定请求是否应该被允许。
     */
    public static <T extends Request> ThrottlingStrategy<T> of(
            BiFunction<ServiceRequestContext, T, CompletionStage<Boolean>> function) {
        return new ThrottlingStrategy<T>(null) {
            @Override
            public CompletionStage<Boolean> accept(ServiceRequestContext ctx, T request) {
                return function.apply(ctx, request);
            }
        };
    }

    /**
     * Returns whether a given request should be treated as failed before it is handled actually.
     * <br/>
     * 在实际处理请求之前， 决定是否把一个请求当做failed的来对待。如果是，则返回true； 否则返回false
     */
    public abstract CompletionStage<Boolean> accept(ServiceRequestContext ctx, T request);

    /**
     * Returns the name of this {@link ThrottlingStrategy}.
     * <br/>
     * 返回该策略的名字
     */
    public String name() {
        return name;
    }
}
