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
package com.linecorp.armeria.server.annotation.decorator;

import java.util.function.Function;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.internal.DefaultValues;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.annotation.DecoratorFactoryFunction;
import com.linecorp.armeria.server.throttling.RateLimitingThrottlingStrategy;
import com.linecorp.armeria.server.throttling.ThrottlingHttpService;

/**
 * A factory which creates a {@link ThrottlingHttpService} decorator with a
 * {@link RateLimitingThrottlingStrategy}.
 *
 * <p>一个用{@link RateLimitingThrottlingStrategy}策略来创建断路服务的工厂"</p>
 */
public final class RateLimitingDecoratorFactoryFunction
        implements DecoratorFactoryFunction<RateLimitingDecorator> {
    /**
     * Creates a new decorator with the specified {@code parameter}.
     */
    @Override
    public Function<Service<HttpRequest, HttpResponse>,
            ? extends Service<HttpRequest, HttpResponse>> newDecorator(RateLimitingDecorator parameter) {
        return ThrottlingHttpService.newDecorator(new RateLimitingThrottlingStrategy<>(
                parameter.value(), DefaultValues.isSpecified(parameter.name()) ? parameter.name() : null));
    }
}
