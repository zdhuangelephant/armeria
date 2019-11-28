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
package com.linecorp.armeria.client.retry;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;

import javax.annotation.Nullable;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.util.Exceptions;

/**
 * Determines whether a failed request should be retried.
 * If you need to determine by looking into the {@link Response}, use {@link RetryStrategyWithContent}.
 * <br/>
 * 重连策略。
 * 该类决定了一个失败的request是否应该尝试重连。
 * 如果你想依赖于响应结果来决定是否重连，可参考{@link RetryStrategyWithContent}
 */
@FunctionalInterface
public interface RetryStrategy {

    /**
     * The default {@link Backoff} implementation.
     * <br/>
     * 默认的{@link Backoff}实现方式
     * @deprecated Use {@link Backoff#ofDefault()}.
     */
    @Deprecated
    Backoff defaultBackoff = Backoff.ofDefault();

    /**
     * A {@link RetryStrategy} that defines a retry should not be performed.
     * <br/>
     * 声明了一个从来不会进行重连操作的策略
     */
    static RetryStrategy never() {
        return (ctx, cause) -> CompletableFuture.completedFuture(null);
    }

    /**
     * A {@link RetryStrategy} that retries only on {@link UnprocessedRequestException} with
     * the {@link Backoff#ofDefault()}.
     */
    static RetryStrategy onUnprocessed() {
        return onUnprocessed(Backoff.ofDefault());
    }

    /**
     * A {@link RetryStrategy} that retries only on {@link UnprocessedRequestException} with the specified
     * {@link Backoff}.
     * <br/>
     *  返回一个RetryStrategy， 仅仅当异常类型是UnprocessedRequestException的时候，才会返回指定的backoff，否则返回null
     */
    static RetryStrategy onUnprocessed(Backoff backoff) {
        requireNonNull(backoff, "backoff");
        return onStatus((status, thrown) -> {
            if (thrown != null && Exceptions.peel(thrown) instanceof UnprocessedRequestException) {
                return backoff;
            }
            return null;
        });
    }

    /**
     * Returns the {@link RetryStrategy} that retries the request with the {@link Backoff#ofDefault()}
     * when the response status matches {@link HttpStatusClass#SERVER_ERROR} or an {@link Exception} is raised.
     * <br/>
     * 返回一个RetryStrategy，通过传入的指定的backoff，并且响应结果必须匹配500的错误
     */
    static RetryStrategy onServerErrorStatus() {
        return onServerErrorStatus(Backoff.ofDefault());
    }

    /**
     * Returns the {@link RetryStrategy} that retries the request with the specified {@code backoff}
     * when the response status matches {@link HttpStatusClass#SERVER_ERROR} or an {@link Exception} is raised.
     * <br/>
     * 返回一个RetryStrategy， 通过传入的backoff，并且响应结果必须匹配{@link HttpStatusClass#SERVER_ERROR}或者抛出异常
     */
    static RetryStrategy onServerErrorStatus(Backoff backoff) {
        requireNonNull(backoff, "backoff");
        return onStatus((status, thrown) -> {
            if (thrown != null || (status != null && status.codeClass() == HttpStatusClass.SERVER_ERROR)) {
                return backoff;
            }
            return null;
        });
    }

    /**
     * Returns the {@link RetryStrategy} that decides to retry the request using the specified
     * {@code backoffFunction}.
     * <br/>
     * 依赖于传入的参数backoffFunction，根据响应结果的状态码来返回一个{@link RetryStrategy}
     * @param backoffFunction the {@link BiFunction} that returns the {@link Backoff} or {@code null}
     *                        according to the {@link HttpStatus} and {@link Throwable}
     */
    static RetryStrategy onStatus(
            BiFunction<HttpStatus, Throwable, Backoff> backoffFunction) {
        // TODO(trustin): Apply a different backoff for UnprocessedRequestException.
        return new HttpStatusBasedRetryStrategy(backoffFunction);
    }

    /**
     * Tells whether the request sent with the specified {@link ClientRequestContext} requires a retry or not.
     * Implement this method to return a {@link CompletionStage} and to complete it with a desired
     * {@link Backoff}. To stop trying further, complete it with {@code null}.
     *
     * <p>To retrieve the {@link ResponseHeaders}, you can use the specified {@link ClientRequestContext}:
     * <pre>{@code
     * CompletionStage<Backoff> shouldRetry(ClientRequestContext ctx, @Nullable Throwable cause) {
     *     if (cause != null) {
     *         return CompletableFuture.completedFuture(backoff);
     *     }
     *
     *     ResponseHeaders responseHeaders = ctx.log().responseHeaders();
     *     if (responseHeaders.status().codeClass() == HttpStatusClass.SERVER_ERROR) {
     *         return CompletableFuture.completedFuture(backoff);
     *     }
     *     ...
     * }
     * }</pre>
     *
     * @param ctx the {@link ClientRequestContext} of this request
     * @param cause the {@link Throwable} which is raised while sending a request. {@code null} it there's no
     *              exception.
     */
    CompletionStage<Backoff> shouldRetry(ClientRequestContext ctx, @Nullable Throwable cause);
}
