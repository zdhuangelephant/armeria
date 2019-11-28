/*
 * Copyright 2018 LINE Corporation
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

package com.linecorp.armeria.client.circuitbreaker;

import java.util.concurrent.CompletionStage;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.Response;

/**
 * Determines whether a {@link Response} should be reported as a success or a failure to a
 * {@link CircuitBreaker} using the content of a {@link Response}. If you just need the HTTP headers
 * to make a decision, use {@link CircuitBreakerStrategy} for efficiency.
 * <br/>
 * 根据响应的内容来决定本次请求是否以成功或者失败报告给{@link CircuitBreaker}，如果你想通过Http响应头来当做依据，则可以使用{@link CircuitBreakerStrategy}
 * @param <T> the response type
 */
@FunctionalInterface
public interface CircuitBreakerStrategyWithContent<T extends Response> {

    /**
     * Returns a {@link CompletionStage} that contains {@code true}, {@code false} or
     * {@code null} according to the specified {@link Response}.
     * If {@code true} is returned, {@link CircuitBreaker#onSuccess()} is called so that the
     * {@link CircuitBreaker} increases its success count and uses it to make a decision
     * to close or open the circuit. If {@code false} is returned, it works the other way around.
     * If {@code null} is returned, the {@link CircuitBreaker} ignores it.
     * <br/>
     * <br/>
     * <br/>
     * <br/>
     * <br/>
     *
     * <table>
     * <caption>该方法有且仅会将会返回其一，可能是true，false，null的{@link CompletionStage}</caption>
     * <tr><th>说明</th><th>描述</th></tr>
     *
     * <tr><td>true</td>
     * <td>将会调用{@link CircuitBreaker#onSuccess()},所以增加成功次数，并通过它来判断是关闭/开启一个中断</td></tr>
     *
     * <tr><td>false</td>
     * <td>将会调用{@link CircuitBreaker#onFailure()}</td></tr>
     *
     * <tr>null</td>
     * <td>将会被忽略</td></tr>
     *
     * </table>
     * @param ctx the {@link ClientRequestContext} of this request
     * @param response the {@link Response} from the server
     */
    CompletionStage<Boolean> shouldReportAsSuccess(ClientRequestContext ctx, T response);
}
