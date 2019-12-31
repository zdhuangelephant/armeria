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
 * Specifies a media type which would be produced by the service method or class.
 * Response的类型
 *
 * <br/>
 * <prev>{@code
 *
 * // 当请求方法 和 请求路径都一致的时候
 * public class MyAnnotatedService {
 *
 *     @Get("/hello")
 *     @Produces("text/plain")
 *     public HttpResponse helloText() {
 *         // 给客户端返回文本数据
 *         return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "Armeria");
 *     }
 *
 *     @Get("/hello")
 *     @Produces("application/json")
 *     public HttpResponse helloJson() {
 *         // 给客户端返回json数据
 *         return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, "{ \"name\": \"Armeria\" }");
 *     }
 * }
 * }</prev>
 */
@Repeatable(ProducesGroup.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE, ElementType.METHOD })
public @interface Produces {

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
