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
package com.linecorp.armeria.server.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation for an additional HTTP header.
 *
 * <br/>
 * 对某些url请求，添加格外的请求头
 *
 * <prev>{@code
 *
 * // 如果方法级别的和类级别的名字冲突，则方法级别的会覆盖类级别的值
 * @AdditionalHeader(name = "custom-header", value = "custom-value")
 * @AdditionalTrailer(name = "custom-trailer", value = "custom-value")
 * public class MyAnnotatedService {
 *     @Get("/hello")
 *     @AdditionalHeader(name = "custom-header", value = "custom-overwritten")
 *     @AdditionalTrailer(name = "custom-trailer", value = "custom-overwritten")
 *     public HttpResponse hello() { ... }
 * }
 * }</prev>
 */
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(AdditionalHeaders.class)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface AdditionalHeader {

    /**
     * The name of the HTTP header to set.
     */
    String name();

    /**
     * The values of the HTTP header to set.
     */
    String[] value();
}
