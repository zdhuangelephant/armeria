/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.client;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;

/**
 * Decorates a {@link Client}. Use {@link DecoratingClient} if your {@link Client} has different
 * {@link Request} or {@link Response} type from the {@link Client} being decorated.
 * <br/>
 * 装饰客户端。
 * 这个类很厉害的！
 * @param <I> the {@link Request} type of the {@link Client} being decorated
 * @param <O> the {@link Response} type of the {@link Client} being decorated
 * <br/>
 * <br/>
 *
 * <table>
 * <caption>SimpleDecoratingClient其直接、间接实现类概要</caption>
 * <tr><th>已知实现类</th><th>描述</th></tr>
 *
 * <tr><td>circuitbreaker包下的CircuitBreakerClient</td>
 * <td>中断器客户端是其实现的客户端，抽象楼子类</td></tr>
 *
 * <tr><td>logging包下LoggingClient</td>
 * <td>专门用来记录日志的客户端，是其抽象类子类</td></tr>
 *
 * <tr><td>metric包下的MetricCollectingClient</td>
 * <td>搜集指标的向注册中心发送的客户端，是其抽象类子类</td></tr>
 *
 * <tr><td>SimpleDecoratingRpcClient</td>
 * <td>Rpc调用的客户端，是其抽象类子类</td></tr>
 *
 * <tr>SimpleDecoratingHttpClient</td>
 * <td>Http调用的客户端，是其抽象类子类</td></tr>
 *
 * <tr>FunctionalDecoratingClient</td>
 * <td>通过一个传入的function，实现了一个函数式客户端，是其具体实现子类</td></tr>
 *
 * <tr>limit包下的ConcurrencyLimitingClient</td>
 * <td>限流客户端，是其抽象类子类</td></tr>
 *
 * <tr>retry包下的RetryingClient</tr>
 * <td>重连客户端，是其抽象类子类</td>
 * </table>
 */
public abstract class SimpleDecoratingClient<I extends Request, O extends Response>
        extends DecoratingClient<I, O, I, O> {

    /**
     * Creates a new instance that decorates the specified {@link Client}.
     * 创建一个装饰的客户端
     */
    protected SimpleDecoratingClient(Client<I, O> delegate) {
        super(delegate);
    }
}
