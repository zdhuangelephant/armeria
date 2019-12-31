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

package com.linecorp.armeria.server;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;

/**
 * A functional interface that enables building a {@link SimpleDecoratingService} with
 * {@link Service#decorate(DecoratingServiceFunction)}.
 * <br/>
 * 一个功能性接口，使得可以通过{@link Service#decorate(DecoratingServiceFunction)}来绑定SimpleDecoratingService
 *
 * <br/>
 * <prev>{@code
 * public class MyDecorator implements DecoratingHttpServiceFunction {
 *     @Override
 *     public HttpResponse serve(HttpService delegate, ServiceRequestContext ctx, HttpRequest req) {
 *         // ... Do something ...
 *         return delegate.serve(ctx, req);
 *     }
 * }
 *
 * // MyDecorator首先会处理请求，其次AnotherDecorator再次处理请求，最后hello()才会处理请求。
 *
 * @Decorator(MyDecorator.class)
 * public class MyAnnotatedService {
 *     @Decorator(AnotherDecorator.class)
 *     @Get("/hello")
 *     public HttpResponse hello() { ... }
 * }
 *
 *
 * }</prev>
 *
 *
 * @param <I> the {@link Request} type
 * @param <O> the {@link Response} type
 */
@FunctionalInterface
public interface DecoratingServiceFunction<I extends Request, O extends Response> {
    /**
     * Serves an incoming {@link Request}.
     *
     * @param delegate the {@link Service} being decorated by this function
     * @param ctx the context of the received {@link Request}
     * @param req the received {@link Request}
     *
     * @return the {@link Response}
     */
    O serve(Service<I, O> delegate, ServiceRequestContext ctx, I req) throws Exception;
}
