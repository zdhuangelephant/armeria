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
 * A functional interface that enables building a {@link SimpleDecoratingClient} with
 * {@link ClientBuilder#decorator(DecoratingClientFunction)} and
 * {@link ClientBuilder#rpcDecorator(DecoratingClientFunction)}.
 * <p>一个功能接口，可以用{@link ClientBuilder#decorator(DecoratingClientFunction)}和{@link ClientBuilder#rpcDecorator(DecoratingClientFunction)}来构建SimpleDecoratingClient</p>
 * @param <I> the {@link Request} type
 * @param <O> the {@link Response} type
 */
@FunctionalInterface
public interface DecoratingClientFunction<I extends Request, O extends Response> {
    /**
     * Sends a {@link Request} to a remote {@link Endpoint}, as specified in
     * {@link ClientRequestContext#endpoint()}.
     * <br/>
     * 发送一个req到远程的Endpoint,这个Endpoint是在{@link ClientRequestContext#endpoint()}内指定的。
     *
     * @param delegate the {@link Client} being decorated by this function.     被function正在装饰的Client
     * @param ctx the context of the {@link Request} being sent.    req所属的ctx
     * @param req the {@link Request} being sent.       正在被发送的req
     *
     * @return the {@link Response} to be received.     返回处理以后的response
     */
    O execute(Client<I, O> delegate, ClientRequestContext ctx, I req) throws Exception;
}
