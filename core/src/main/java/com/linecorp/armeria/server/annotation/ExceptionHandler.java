/*
 * Copyright 2017 LINE Corporation
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
 * Specifies an {@link ExceptionHandlerFunction} class which handles exceptions throwing from an
 * annotated service method.
 *
 * <br/>
 * <pre>{@code
 *
 * // 演示了异常处理器的起作用的顺序。
 *
 * @ExceptionHandler(MyClassExceptionHandler3.class)           // order 3
 * @ExceptionHandler(MyClassExceptionHandler4.class)           // order 4
 * public class MyAnnotatedService {
 *     @Get("/hello")
 *     @ExceptionHandler(MyMethodExceptionHandler1.class)      // order 1
 *     @ExceptionHandler(MyMethodExceptionHandler2.class)      // order 2
 *     public HttpResponse hello() { ... }
 * }
 *
 * // ...
 *
 * sb.annotatedService(new MyAnnotatedService(),
 *                     new MyGlobalExceptionHandler5(),        // order 5
 *                     new MyGlobalExceptionHandler6());       // order 6
 * }</pre>
 */
@Repeatable(ExceptionHandlers.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE, ElementType.METHOD })
public @interface ExceptionHandler {

    /**
     * {@link ExceptionHandlerFunction} implementation type. The specified class must have an accessible
     * default constructor.
     */
    Class<? extends ExceptionHandlerFunction> value();
}
