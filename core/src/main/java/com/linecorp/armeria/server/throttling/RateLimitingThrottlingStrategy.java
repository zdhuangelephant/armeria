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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.completedFuture;

import java.util.concurrent.CompletionStage;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.RateLimiter;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * A {@link ThrottlingStrategy} that provides a throttling strategy based on QPS.
 * The throttling works by examining the number of requests from the {@link ThrottlingService} from
 * the beginning, and throttling if the QPS is found exceed the specified tolerable maximum.
 * <br/>
 * 基于QPS内， 提供了一种访问频率限制的策略。
 * {@link ThrottlingService}实例内， 请求次数是否通过阈值上限来决定是否放行本次请求。
 *
 */
public final class RateLimitingThrottlingStrategy<T extends Request> extends ThrottlingStrategy<T> {
    /**
     * 通过谷歌的{@link RateLimiter}的令牌桶算法
     *
     * tips
     * 漏桶算法假定了系统处理请求的速率是恒定的，其无法解决系统突发流量的情况
     *
     *
     * RateLimiter基于令牌桶算法，它的核心思想主要有：
     * 1、响应本次请求之后，动态计算下一次可以服务的时间，如果下一次请求在这个时间之前则需要进行等待。
     *    SmoothRateLimiter 类中的 nextFreeTicketMicros 属性表示下一次可以响应的时间。
     *    例如，如果我们设置QPS为1，本次请求处理完之后，那么下一次最早的能够响应请求的时间一秒钟之后。
     * 2、RateLimiter 的子类 SmoothBursty 支持处理突发流量请求，例如，我们设置QPS为1，在十秒钟之内没有请求，
     *    那么令牌桶中会有10个（假设设置的最大令牌数大于10）空闲令牌，如果下一次请求是 acquire(20) ，则不需要等待20秒钟，
     *    因为令牌桶中已经有10个空闲的令牌。SmoothRateLimiter 类中的 storedPermits 就是用来表示当前令牌桶中的空闲令牌数。
     * 3、RateLimiter 子类 SmoothWarmingUp 不同于 SmoothBursty ，它存在一个“热身”的概念。
     *    它将 storedPermits 分成两个区间值：[0, thresholdPermits) 和 [thresholdPermits, maxPermits]。
     *    当请求进来时，如果当前系统处于"cold"的状态，从 [thresholdPermits, maxPermits] 区间去拿令牌，
     *    所需要等待的时间会长于从区间 [0, thresholdPermits) 拿相同令牌所需要等待的时间。当请求增多，storedPermits 减少到 thresholdPermits 以下时，
     *    此时拿令牌所需要等待的时间趋于稳定。这也就是所谓“热身”的过程。这个过程后面会详细分析。
     *
     */
    private final RateLimiter rateLimiter;

    /**
     * Creates a new strategy with specified name.
     * <br/>
     * 用指定的名字创建一个实例
     *
     * @param requestPerSecond the number of requests per one second this {@link ThrottlingStrategy} accepts.  每秒钟要接受的请求次数
     */
    public RateLimitingThrottlingStrategy(double requestPerSecond, @Nullable String name) {
        super(name);
        checkArgument(requestPerSecond > 0, "requestPerSecond: %s (expected: > 0)", requestPerSecond);
        rateLimiter = RateLimiter.create(requestPerSecond);
    }

    /**
     * Creates a new strategy.
     * <br/>
     * 创建一个请求策略，每秒接受的请求次数和默认的名字。
     * @param requestPerSecond the number of requests per one second this {@link ThrottlingStrategy} accepts.
     */
    public RateLimitingThrottlingStrategy(double requestPerSecond) {
        this(requestPerSecond, null);
    }

    @VisibleForTesting
    RateLimitingThrottlingStrategy(RateLimiter rateLimiter) {
        this.rateLimiter = requireNonNull(rateLimiter, "rateLimiter");
    }

    @Override
    public CompletionStage<Boolean> accept(ServiceRequestContext ctx, T request) {
        return completedFuture(rateLimiter.tryAcquire());
    }
}
