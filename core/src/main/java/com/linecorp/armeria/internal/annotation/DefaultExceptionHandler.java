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

package com.linecorp.armeria.internal.annotation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.server.HttpResponseException;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.ExceptionVerbosity;

/**如果用户没有{@link ExceptionHandler}注解来标记某个来当做异常处理器的话， 则默认会使用该{@link DefaultExceptionHandler}
 * <br/>
 * A default exception handler is used when a user does not specify exception handlers
 * by {@link ExceptionHandler} annotation. It returns:
 * <ul>
 *     <li>an {@link HttpResponse} with {@code 400 Bad Request} status code when the cause is an
 *     {@link IllegalArgumentException}, or</li>
 *     <li>an {@link HttpResponse} with the status code that an {@link HttpStatusException} holds, or</li>
 *     <li>an {@link HttpResponse} that an {@link HttpResponseException} holds, or</li>
 *     <li>an {@link HttpResponse} with {@code 500 Internal Server Error}.</li>
 * </ul>
 *
 * <br/>
 * <pre>
 * // 自定义一个异常处理器
 * {@code
 *     public class MyExceptionHandler implements ExceptionHandlerFunction {
 *       @Override
 *       public HttpResponse handleException(ServiceRequestContext ctx, HttpRequest req, Throwable cause) {
 *         if (cause instanceof MyServiceException) {
 *             return HttpResponse.of(HttpStatus.CONFLICT);
 *         }
 *
 *         // To the next exception handler.
 *         return ExceptionHandlerFunction.fallthrough();
 *       }
 *     }
 *
 *     // 上面定义了异常处理器以后， 可以在类级别进行使用
 *     @ExceptionHandler(MyExceptionHandler.class)
 *     public class MyAnnotatedService {
 *       @Get("/hello")
 *       public HttpResponse hello() { ... }
 *     }
 *
 *     // 上面定义了异常处理器以后， 可以在方法级别进行使用
 *     public class MyAnnotatedService {
 *       @Get("/hello")
 *       @ExceptionHandler(MyExceptionHandler.class)
 *       public HttpResponse hello() { ... }
 *     }
 * }
 * </pre>
 */
final class DefaultExceptionHandler implements ExceptionHandlerFunction {
    private static final Logger logger = LoggerFactory.getLogger(DefaultExceptionHandler.class);

    @Override
    public HttpResponse handleException(RequestContext ctx, HttpRequest req, Throwable cause) {
        if (cause instanceof IllegalArgumentException) {
            return HttpResponse.of(HttpStatus.BAD_REQUEST);
        }

        if (cause instanceof HttpStatusException) {
            return HttpResponse.of(((HttpStatusException) cause).httpStatus());
        }

        if (cause instanceof HttpResponseException) {
            return ((HttpResponseException) cause).httpResponse();
        }

        if (Flags.annotatedServiceExceptionVerbosity() == ExceptionVerbosity.UNHANDLED &&
            logger.isWarnEnabled()) {
            logger.warn("{} Unhandled exception from an annotated service:", ctx, cause);
        }

        return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
