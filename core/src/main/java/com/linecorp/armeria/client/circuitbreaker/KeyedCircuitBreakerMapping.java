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

package com.linecorp.armeria.client.circuitbreaker;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RpcRequest;

/**
 * A {@link CircuitBreakerMapping} that binds a {@link CircuitBreaker} to its key. {@link KeySelector} is used
 * to resolve the key from a {@link Request}. If there is no circuit breaker bound to the key, a new one is
 * created by using the given circuit breaker factory.
 * <br/>
 * <br/>
 * 把CircuitBreaker通过某个key绑定到了Reqeust。
 * {@link KeySelector}的作用是从Request内解析出CircuitBreaker。
 * 如果key没有绑定CircuitBreaker，则通过工厂创建一个新的CircuitBreaker实例
 *
 * @param <K> the key type
 */
public class KeyedCircuitBreakerMapping<K> implements CircuitBreakerMapping {

    static final CircuitBreakerMapping defaultMapping =
            new KeyedCircuitBreakerMapping<>(KeySelector.HOST, CircuitBreaker::of);

    private final ConcurrentMap<K, CircuitBreaker> mapping = new ConcurrentHashMap<>();

    /**
     * CircuitBreaker的解析器，通过解析某一个key
     */
    private final KeySelector<K> keySelector;

    /**
     * 创建CircuitBreaker的工厂
     */
    private final Function<K, CircuitBreaker> factory;

    /**
     * Creates a new {@link KeyedCircuitBreakerMapping} with the given {@link KeySelector} and
     * {@link CircuitBreaker} factory.
     *
     * @param keySelector A function that returns the key of the given {@link Request}.
     * @param factory A function that takes a key and creates a new {@link CircuitBreaker} for the key.
     */
    public KeyedCircuitBreakerMapping(KeySelector<K> keySelector, Function<K, CircuitBreaker> factory) {
        this.keySelector = requireNonNull(keySelector, "keySelector");
        this.factory = requireNonNull(factory, "factory");
    }

    @Override
    public CircuitBreaker get(ClientRequestContext ctx, Request req) throws Exception {
        final K key = keySelector.get(ctx, req);
        final CircuitBreaker circuitBreaker = mapping.get(key);
        if (circuitBreaker != null) {
            return circuitBreaker;
        }
        return mapping.computeIfAbsent(key, mapKey -> factory.apply(key));
    }

    /**
     * Returns the mapping key of the given {@link Request}.
     * <br/>
     * 返回与被给的{@link Request}相对应的Key
     */
    @FunctionalInterface
    public interface KeySelector<K> {

        /**
         * A {@link KeySelector} that returns remote method name as a key.
         * <br/>
         *  把远程调用的方法名字当做Key的{@link KeySelector}
         */
        KeySelector<String> METHOD =
                (ctx, req) -> req instanceof RpcRequest ? ((RpcRequest) req).method() : ctx.method().name();

        /**
         * A {@link KeySelector} that returns a key consisted of remote host name, IP address and port number.
         * <br/>
         * 把远程调用的主机，ip，端口当做Key的{@link KeySelector}
         */
        KeySelector<String> HOST =
                (ctx, req) -> {
                    final Endpoint endpoint = ctx.endpoint();
                    if (endpoint == null) {
                        return "UNKNOWN";
                    } else if (endpoint.isGroup()) {
                        return endpoint.authority();
                    } else {
                        final String ipAddr = endpoint.ipAddr();
                        if (ipAddr == null || endpoint.isIpAddrOnly()) {
                            return endpoint.authority();
                        } else {
                            return endpoint.authority() + '/' + ipAddr;
                        }
                    }
                };

        /**
         * A {@link KeySelector} that returns a key consisted of remote host name, IP address, port number
         * and method name.
         * <br/>
         * 把远程调用的主机，ip，端口、和远程调用方法名字当做Key的{@link KeySelector}
         */
        KeySelector<String> HOST_AND_METHOD =
                (ctx, req) -> HOST.get(ctx, req) + '#' + METHOD.get(ctx, req);

        /**
         * Returns the mapping key of the given {@link Request}.
         * <br/>
         * 返回于传入的Request相对应的Key
         */
        K get(ClientRequestContext ctx, Request req) throws Exception;
    }
}
