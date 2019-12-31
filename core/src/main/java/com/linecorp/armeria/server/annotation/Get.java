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

package com.linecorp.armeria.server.annotation;

import static com.linecorp.armeria.internal.DefaultValues.UNSPECIFIED;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.linecorp.armeria.common.HttpMethod;

/**
 * Annotation for mapping {@link HttpMethod#GET} onto specific method.
 *
 * <pre>{@code
 * // Getting an HTTP header
 * public class MyAnnotatedService {
 *
 *     @Get("/hello1")
 *     public HttpResponse hello1(@Header("Authorization") String auth) { ... }
 *
 *     @Post("/hello2")
 *     public HttpResponse hello2(@Header("Content-Length") long contentLength) { ... }
 *
 *     @Post("/hello3")
 *     public HttpResponse hello3(@Header("Forwarded") List<String> forwarded) { ... }
 *
 *     @Post("/hello4")
 *     public HttpResponse hello4(@Header("Forwarded") Optional<Set<String>> forwarded) { ... }
 * }
 *
 * // Other classes automatically injected
 *  - ServiceRequestContext
 *  - HttpRequest
 *  - AggregatedHttpRequest
 *  - HttpParameters
 *  - Cookies
 * public class MyAnnotatedService {
 *
 *     @Get("/hello1")
 *     public HttpResponse hello1(ServiceRequestContext ctx, HttpRequest req) {
 *         // Use the context and request inside a method.
 *     }
 *
 *     @Post("/hello2")
 *     public HttpResponse hello2(AggregatedHttpRequest aggregatedRequest) {
 *         // Armeria aggregates the received HttpRequest and calls this method with the aggregated request.
 *     }
 *
 *     @Get("/hello3")
 *     public HttpResponse hello3(HttpParameters httpParameters) {
 *         // 'httpParameters' holds the parameters parsed from a query string of a request.
 *     }
 *
 *     @Post("/hello4")
 *     public HttpResponse hello4(HttpParameters httpParameters) {
 *         // If a request has a url-encoded form as its body, it can be accessed via 'httpParameters'.
 *     }
 *
 *     @Post("/hello5")
 *     public HttpResponse hello5(Cookies cookies) {
 *         // If 'Cookie' header exists, it will be injected into the specified 'cookies' parameter.
 *     }
 * }
 * }</pre>
 *
 *
 *
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Get {

    /**
     * A path pattern for the annotated method.
     */
    String value() default UNSPECIFIED;
}
