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

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;

/**
 * <br/>
 * 该类的直接子类是{@link SimpleDecoratingClient}。
 * 该类还是变种责任连模式的具体实现
 * <br/>
 *
 * Decorates a {@link Client}. Use {@link SimpleDecoratingClient},
 * {@link ClientBuilder#decorator(DecoratingClientFunction)} or
 * {@link ClientBuilder#rpcDecorator(DecoratingClientFunction)} if your {@link Client} has the same
 * {@link Request} and {@link Response} type with the {@link Client} being decorated.
 *
 * @param <T_I> the {@link Request} type of the {@link Client} being decorated 被装饰Client的Request类型
 * @param <T_O> the {@link Response} type of the {@link Client} being decorated 被装饰Client的Response类型
 * @param <R_I> the {@link Request} type of this {@link Client} 当前Client的Request类型
 * @param <R_O> the {@link Response} type of this {@link Client} 当前Client的Response类型
 */
public abstract class DecoratingClient<T_I extends Request, T_O extends Response,
                                       R_I extends Request, R_O extends Response> implements Client<R_I, R_O> {

    private final Client<T_I, T_O> delegate;

    /**
     * Creates a new instance that decorates the specified {@link Client}.
     * 创建装饰了指定Client的某实例
     */
    protected DecoratingClient(Client<T_I, T_O> delegate) {
        this.delegate = requireNonNull(delegate, "delegate");
    }

    /**
     * Returns the {@link Client} being decorated.
     */
    @SuppressWarnings("unchecked")
    protected final <T extends Client<T_I, T_O>> T delegate() {
        return (T) delegate;
    }

    @Override
    public String toString() {
        final String simpleName = getClass().getSimpleName();
        final String name = simpleName.isEmpty() ? getClass().getName() : simpleName;
        return name + '(' + delegate + ')';
    }
}
