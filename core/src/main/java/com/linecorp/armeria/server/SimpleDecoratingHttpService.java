/*
 * Copyright 2019 LINE Corporation
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

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;

/**
 * An {@link HttpService} that decorates another {@link HttpService}.
 * <p>如果期望我们的服务是可以重用的， 如此我们推荐您定义一个顶级SimpleDecoratingHttpService实现类，或者定义一个顶级SimpleDecoratingRpcService实现类</p>
 *
 * @see Service#decorate(DecoratingServiceFunction)
 *
 * <pre>{@code
 * public class AuthService extends SimpleDecoratingHttpService {
 *     public AuthService(HttpService delegate) {
 *         super(delegate);
 *     }
 *
 *     public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
 *         if (!authenticate(req)) {
 *             // Authentication failed; fail the request.
 *             return HttpResponse.of(HttpStatus.UNAUTHORIZED);
 *
 *         }
 *
 *         HttpService delegate = delegate();
 *         return delegate.serve(ctx, req);
 *     }
 * }
 *
 * ServerBuilder sb = Server.builder();
 * // Using a lambda expression:
 * sb.serviceUnder("/web", service.decorate(delegate -> new AuthService(delegate)));
 * </pre>
 */
public abstract class SimpleDecoratingHttpService extends SimpleDecoratingService<HttpRequest, HttpResponse>
        implements HttpService {
    /**
     * Creates a new instance that decorates the specified {@link Service}.
     */
    protected SimpleDecoratingHttpService(Service<HttpRequest, HttpResponse> delegate) {
        super(delegate);
    }
}
