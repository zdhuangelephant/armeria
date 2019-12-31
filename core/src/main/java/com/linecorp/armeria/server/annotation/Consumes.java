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

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Specifies a media type which would be consumed by the service method or class.
 * <br/>
 * 指定可以被服务端消费的Media类型
 *
 * <br/>
 * <prev>{@code
 * public class MyAnnotatedService {
 *
 *     // POST /hello HTTP/1.1
 *     // Content-Type: text/plain
 *     // Content-Length: 7
 *     @Post("/hello")
 *     @Consumes("text/plain")
 *     public HttpResponse helloText(AggregatedHttpRequest request) {
 *         // Get a text content by calling request.contentAscii().
 *     }
 *
 *     // POST /hello HTTP/1.1
 *     // Content-Type: application/json
 *     // Content-Length: 21
 *     @Post("/hello")
 *     @Consumes("application/json")
 *     public HttpResponse helloJson(AggregatedHttpRequest request) {
 *         // Get a JSON object by calling request.contentUtf8().
 *     }
 * }
 *
 * ========================================================================
 *
 * public class MyAnnotatedService {
 *
 *     // 如果客户端发送了一个服务端未曾注册的media类型，我们可以通过做一个所以Media类型的匹配，但是除了"application/json"类型，因为下面的方法已经有匹配了。
 *     @Post("/hello")
 *     public HttpResponse helloCatchAll(AggregatedHttpR1equest request) {
 *         // Get a content by calling request.content() and handle it as a text document or something else.
 *     }
 *
 *     @Post("/hello")
 *     @Consumes("application/json")
 *     public HttpResponse helloJson(AggregatedHttpRequest request) {
 *         // Get a JSON object by calling request.contentUtf8().
 *     }
 * }
 *
 * ========================================================================
 *
 * // 如果感觉Armeria提供的Media类型不够我们用的，您可以自己扩展，如下使用姿势:
 * @Retention(RetentionPolicy.RUNTIME)
 * @Target({ ElementType.TYPE, ElementType.METHOD })
 * @Consumes("application/xml")
 * public @interface MyConsumableType {}
 *
 * @Retention(RetentionPolicy.RUNTIME)
 * @Target({ ElementType.TYPE, ElementType.METHOD })
 * @Produces("application/xml")
 * public @interface MyProducibleType {}
 *
 * // 上面定义完毕以后， 则向下面的使用方式。
 * public class MyAnnotatedService {
 *     @Post("/hello")
 *     @MyConsumableType  // the same as @Consumes("application/xml")
 *     @MyProducibleType  // the same as @Produces("application/xml")
 *     public MyResponse hello(MyRequest myRequest) { ... }
 * }
 *
 * }
 * }</prev>
 */
@Repeatable(ConsumesGroup.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE, ElementType.METHOD })
public @interface Consumes {

    /**
     * A media type string. For example,
     * <ul>
     *   <li>{@code application/json; charset=utf-8}</li>
     *   <li>{@code application/xml}</li>
     *   <li>{@code application/octet-stream}</li>
     *   <li>{@code text/html}</li>
     * </ul>
     */
    String value();
}
