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

import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;

/**
 * A set of {@link Function}s that transforms a {@link Client} into another.
 * <br/>
 * 一系列的方法，用来是从一个客户端传向另外一个客户端。
 */
public final class ClientDecoration {

    /**
     * A {@link ClientDecoration} that decorates no {@link Client}.
     */
    public static final ClientDecoration NONE = new ClientDecoration(Collections.emptyList());

    /**
     * Creates a new instance from a single decorator {@link Function}.
     *
     * @param requestType the type of the {@link Request} that the {@code decorator} is interested in
     * @param responseType the type of the {@link Response} that the {@code decorator} is interested in
     * @param decorator the {@link Function} that transforms a {@link Client} to another
     * @param <T> the type of the {@link Client} being decorated
     * @param <R> the type of the {@link Client} produced by the {@code decorator}
     */
    public static <T extends Client<I, O>, R extends Client<I, O>, I extends Request, O extends Response>
    ClientDecoration of(Class<I> requestType, Class<O> responseType, Function<T, R> decorator) {
        return new ClientDecorationBuilder().add(requestType, responseType, decorator).build();
    }

    private final List<Entry<?, ?>> entries;

    ClientDecoration(List<Entry<?, ?>> entries) {
        this.entries = Collections.unmodifiableList(entries);
    }

    List<Entry<?, ?>> entries() {
        return entries;
    }

    /**
     * Decorates the specified {@link Client} using the decorator with matching {@code requestType} and
     * {@code responseType}.
     * <br/>
     * 对指定的Client通过判断requestType和responseType是否匹配，来进行装饰。
     *
     * @param requestType the type of the {@link Request} the specified {@link Client} accepts.     被装饰的Client的Request的类型
     * @param responseType the type of the {@link Response} the specified {@link Client} produces.      装饰以后的Client的Response的类型
     * @param client the {@link Client} being decorated.   被装饰的Client类型
     * @param <I> {@code requestType}
     * @param <O> {@code responseType}
     *
     * {@linkplain         //String.class.isInstance("hello") 等价于 "hello" instanceof String
     *                     // 自身类.class.isAssignableFrom(自身类或子类.class)  返回true}
     */
    public <I extends Request, O extends Response> Client<I, O> decorate(
            Class<I> requestType, Class<O> responseType, Client<I, O> client) {

        for (Entry<?, ?> e : entries) {
            if (!requestType.isAssignableFrom(e.requestType()) ||
                !responseType.isAssignableFrom(e.responseType())) {
                continue;
            }

            @SuppressWarnings("unchecked")
            final Function<Client<I, O>, Client<I, O>> decorator = ((Entry<I, O>) e).decorator();
            client = decorator.apply(client);
        }

        return client;
    }

    /**
     *  request + response + decorator 的封装类
     * @param <I>
     * @param <O>
     */
    static final class Entry<I extends Request, O extends Response> {
        private final Class<I> requestType;
        private final Class<O> responseType;
        private final Function<Client<I, O>, Client<I, O>> decorator;

        Entry(Class<I> requestType, Class<O> responseType,
              Function<? extends Client<I, O>, ? extends Client<I, O>> decorator) {
            this.requestType = requestType;
            this.responseType = responseType;

            @SuppressWarnings("unchecked")
            final Function<Client<I, O>, Client<I, O>> castDecorator =
                    (Function<Client<I, O>, Client<I, O>>) decorator;
            this.decorator = castDecorator;
        }

        Class<I> requestType() {
            return requestType;
        }

        Class<O> responseType() {
            return responseType;
        }

        Function<Client<I, O>, Client<I, O>> decorator() {
            return decorator;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Entry)) {
                return false;
            }

            final Entry<?, ?> entry = (Entry<?, ?>) o;

            if (!requestType.equals(entry.requestType)) {
                return false;
            }
            if (!responseType.equals(entry.responseType)) {
                return false;
            }

            final Function<?, ?> decorator = this.decorator;
            return decorator == entry.decorator;
        }

        @Override
        public int hashCode() {
            int result = requestType.hashCode();
            result = 31 * result + responseType.hashCode();
            result = 31 * result + System.identityHashCode(decorator);
            return result;
        }

        @Override
        public String toString() {
            return '(' +
                   requestType.getSimpleName() + ", " +
                   responseType.getSimpleName() + ", " +
                   decorator +
                   ')';
        }
    }
}
