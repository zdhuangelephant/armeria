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
package com.linecorp.armeria.server.annotation;

import java.lang.annotation.Annotation;
import java.util.function.Function;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.Service;

/**
 * A decorator factory which is used for a user-defined decorator annotation.
 * <p>装饰工厂：其用于用户自定义的注解中</p>
 *
 * <br/>
 * 我们可以写自己的装饰器， 但是如果我们的装饰器需要参数(十有八九是需要参数的)，此时就需要我们构建自己的装饰器注解了。
 * 自定义装饰注解分为以下几步:
 * <ol>
 *     <li>定义一个装饰器注解{@link com.linecorp.armeria.server.annotation.decorator.LoggingDecorator}</li>
 *     <li>定义一个工厂Function{@link com.linecorp.armeria.server.annotation.decorator.LoggingDecoratorFactoryFunction}, 其实现了{@link DecoratorFactoryFunction}接口</li>
 *     <li>如下使用姿势:{@code
 *         // 这个第二步骤中的工厂Function会根据在类上或方法上@LoggingDecorator注解指定的参数创建LoggingService实例
 *         public class MyAnnotatedService {
 *           @LoggingDecorator(requestLogLevel = LogLevel.INFO)
 *           @Get("/hello1")
 *           public HttpResponse hello1() { ... }
 *
 *           @LoggingDecorator(requestLogLevel = LogLevel.DEBUG, samplingRate = 0.05)
 *           @Get("/hello2")
 *           public HttpResponse hello2() { ... }
 *         }
 *     }</li>
 * </ol>
 */
@FunctionalInterface
public interface DecoratorFactoryFunction<T extends Annotation> {

    /**
     * Creates a new decorator with the specified {@code parameter}.
     * 通过指定的参数创建一个装饰者
     */
    Function<Service<HttpRequest, HttpResponse>,
            ? extends Service<HttpRequest, HttpResponse>> newDecorator(T parameter);
}
