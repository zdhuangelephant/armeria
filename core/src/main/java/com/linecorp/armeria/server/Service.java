/*
 * Copyright 2015 LINE Corporation
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

package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

import java.lang.reflect.Constructor;
import java.util.Optional;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;

/**
 * Handles a {@link Request} received by a {@link Server}.
 * <br/>
 * 处理被Server收到的request请求
 *
 * @param <I> the type of incoming {@link Request}. Must be {@link HttpRequest} or {@link RpcRequest}.  收到的请求， 必需是{@link HttpRequest} or {@link RpcRequest}的类型
 * @param <O> the type of outgoing {@link Response}. Must be {@link HttpResponse} or {@link RpcResponse}. 完成的响应， 必需是{@link HttpResponse} or {@link RpcResponse}的类型
 */
@FunctionalInterface
public interface Service<I extends Request, O extends Response> {

    /**
     * Invoked when this {@link Service} has been added to a {@link Server} with the specified configuration.
     * Please note that this method can be invoked more than once if this {@link Service} has been added more
     * than once.
     * NOTE: 当Service被添加进Server内的时候， 此方法会被调用，如果Service被添加进多次，相应此方法也会被调用多次
     */
    default void serviceAdded(ServiceConfig cfg) throws Exception {}

    /**
     * Serves an incoming {@link Request}.
     *
     * @param ctx the context of the received {@link Request}
     * @param req the received {@link Request}
     *
     * @return the {@link Response}
     *
     *  NOTEf: 真正处理请求的方法
     */
    O serve(ServiceRequestContext ctx, I req) throws Exception;

    /**
     * Undecorates this {@link Service} to find the {@link Service} which is an instance of the specified
     * {@code serviceType}. Use this method instead of an explicit downcast since most {@link Service}s are
     * decorated via {@link #decorate(Function)} and thus cannot be downcast. For example:
     * <pre>{@code
     * Service s = new MyService().decorate(LoggingService.newDecorator())
     *                            .decorate(AuthService.newDecorator());
     * MyService s1 = s.as(MyService.class);
     * LoggingService s2 = s.as(LoggingService.class);
     * AuthService s3 = s.as(AuthService.class);
     * }</pre>
     *
     * @param serviceType the type of the desired {@link Service}
     * @return the {@link Service} which is an instance of {@code serviceType} if this {@link Service}
     *         decorated such a {@link Service}. {@link Optional#empty()} otherwise.
     *
     * 通过上面面的code我们可以得出结论：
     *  通过decorate(xxxService)可以装饰增强原始的Service。但是如果我们想要原始的Service的时候，这个时候就可以通过as方法来进行定向获取自己想要的Service。即装饰增强的逆过程，"脱衣"！
     *
     * NOTE: 这是给Service脱衣服【即卸妆】
     * 根据serviceType去匹配相应的Service并返回，否则返回{@link Optional#empty()}
     */
    default <T> Optional<T> as(Class<T> serviceType) {
        requireNonNull(serviceType, "serviceType");
        // String.class.isInstance("hello") 等价于 "hello" instanceof String
        // 自身类.class.isAssignableFrom(自身类或子类.class)  返回true
        return serviceType.isInstance(this) ? Optional.of(serviceType.cast(this))
                                            : Optional.empty();
    }

    /**
     * Creates a new {@link Service} that decorates this {@link Service} with a new {@link Service} instance
     * of the specified {@code serviceType}. The specified {@link Class} must have a single-parameter
     * constructor which accepts this {@link Service}.
     * <br/>
     * 用传入的serviceType来装饰当前Service并创建一个新的Service。
     * serviceType必须含有至少一个参数的构造方法，用以将this对象当作参数进行反射创建serviceType指定的类。
     */
    default <R extends Service<?, ?>> R decorate(Class<R> serviceType) {
        requireNonNull(serviceType, "serviceType");

        Constructor<?> constructor = null;
        for (Constructor<?> c : serviceType.getConstructors()) {
            //
            if (c.getParameterCount() != 1) {
                continue;
            }
            // 自身类.class.isAssignableFrom(自身类或子类.class)  返回true
            if (c.getParameterTypes()[0].isAssignableFrom(getClass())) {
                constructor = c;
                break;
            }
        }

        if (constructor == null) {
            throw new IllegalArgumentException("cannot find a matching constructor: " + serviceType.getName());
        }

        try {
            return (R) constructor.newInstance(this);
        } catch (Exception e) {
            throw new IllegalStateException("failed to instantiate: " + serviceType.getName(), e);
        }
    }

    /**
     * Creates a new {@link Service} that decorates this {@link Service} with the specified {@code decorator}.
     * NOTE: 通过指定的decorator装饰成一个新的Service
     */
    default <T extends Service<I, O>,
             R extends Service<R_I, R_O>, R_I extends Request, R_O extends Response>
    R decorate(Function<T, R> decorator) {
        @SuppressWarnings("unchecked")
        final R newService = decorator.apply((T) this);

        if (newService == null) {
            throw new NullPointerException("decorator.apply() returned null: " + decorator);
        }

        return newService;
    }

    /**
     * Creates a new {@link Service} that decorates this {@link Service} with the specified
     * {@link DecoratingServiceFunction}.
     */
    default Service<I, O> decorate(DecoratingServiceFunction<I, O> function) {
        return new FunctionalDecoratingService<>(this, function);
    }

    /**
     * Returns whether the given {@code path} and {@code query} should be cached if the service's result is
     * successful. By default, exact path mappings with no input query are cached.
     * 当请求成功被处理的时候， 通过此方法看下该请求结果是否应该被缓存。默认情况下，当没有参数的请求时，是会被缓存的。
     */
    default boolean shouldCachePath(String path, @Nullable String query, Route route) {
        return route.pathType() == RoutePathType.EXACT && query == null;
    }
}
