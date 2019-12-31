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

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.internal.FallthroughException;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Converts a {@code result} object to {@link HttpResponse}. The class implementing this interface would
 * be specified as {@link ResponseConverter} annotation.
 * @see ResponseConverter
 *
 * <br/>
 * <pre>{@code
 * public class MyResponseConverter implements ResponseConverterFunction {
 *     @Override
 *     HttpResponse convertResponse(ServiceRequestContext ctx,
 *                                  ResponseHeaders headers,
 *                                  @Nullable Object result,
 *                                  HttpHeaders trailers) throws Exception {
 *         if (result instanceof MyObject) {
 *             return HttpResponse.of(HttpStatus.OK,
 *                                    MediaType.PLAIN_TEXT_UTF_8,
 *                                    "Hello, %s!", ((MyObject) result).processedName(),
 *                                    trailers);
 *         }
 *
 *         // To the next response converter.
 *         return ResponseConverterFunction.fallthrough();
 *     }
 * }
 *
 * // MyResponseConverter类的在此引用
 * @ResponseConverter(MyResponseConverter.class)
 * public class MyAnnotatedService {
 *
 *     @Post("/hello")
 *     public MyObject hello() {
 *         // MyResponseConverter will be used to make a response.
 *         // ...
 *     }
 *
 *     @Post("/hola")
 *     @ResponseConverter(MySpanishResponseConverter.class)
 *     public MyObject hola() {
 *         // MySpanishResponseConverter will be tried to convert MyObject to a response first.  首先先通过MySpanishResponseConverter进行转换
 *         // MyResponseConverter will be used if MySpanishResponseConverter fell through.  如果不行，再用MyResponseConverter进行转换
 *         // ...
 *     }
 * }
 *
 *
 *
 * // 各种转换器的使用姿势
 * public class MyAnnotatedService {
 *
 *     // JacksonResponseConverterFunction will convert the return values to JSON documents:
 *     @Get("/json1")
 *     @ProducesJson    // the same as @Produces("application/json; charset=utf-8")
 *     public MyObject json1() { ... }
 *
 *     @Get("/json2")
 *     public JsonNode json2() { ... }
 *
 *     // StringResponseConverterFunction will convert the return values to strings:
 *     @Get("/string1")
 *     @ProducesText    // the same as @Produces("text/plain; charset=utf-8")
 *     public int string1() { ... }
 *
 *     @Get("/string2")
 *     public CharSequence string2() { ... }
 *
 *     // ByteArrayResponseConverterFunction will convert the return values to byte arrays:
 *     @Get("/byte1")
 *     @ProducesBinary  // the same as @Produces("application/binary")
 *     public HttpData byte1() { ... }
 *
 *     @Get("/byte2")
 *     public byte[] byte2() { ... }
 * }
 * }</pre>
 */
@FunctionalInterface
public interface ResponseConverterFunction {

    /**
     * Returns {@link HttpResponse} instance corresponds to the given {@code result}.
     * Calls {@link ResponseConverterFunction#fallthrough()} or throws a {@link FallthroughException} if
     * this converter cannot convert the {@code result} to the {@link HttpResponse}.
     *
     * @param headers The HTTP headers that you might want to use to create the {@link HttpResponse}.
     *                The status of headers is {@link HttpStatus#OK} by default or
     *                {@link HttpStatus#NO_CONTENT} if the annotated method returns {@code void},
     *                unless you specify it with {@link StatusCode} on the method.
     *                The headers also will include a {@link MediaType} if
     *                {@link ServiceRequestContext#negotiatedResponseMediaType()} returns it.
     *                If the method returns {@link HttpResult}, this headers is the same headers from
     *                {@link HttpResult#headers()}
     *                Please note that the additional headers set by
     *                {@link ServiceRequestContext#addAdditionalResponseHeader(CharSequence, Object)}
     *                and {@link AdditionalHeader} are not included in this headers.
     * @param result The result of the service method.
     * @param trailers The HTTP trailers that you might want to use to create the {@link HttpResponse}.
     *                 If the annotated method returns {@link HttpResult}, this trailers is the same
     *                 trailers from {@link HttpResult#trailers()}.
     *                 Please note that the additional trailers set by
     *                 {@link ServiceRequestContext#addAdditionalResponseTrailer(CharSequence, Object)}
     *                 and {@link AdditionalTrailer} are not included in this trailers.
     */
    HttpResponse convertResponse(ServiceRequestContext ctx,
                                 ResponseHeaders headers,
                                 @Nullable Object result,
                                 HttpHeaders trailers) throws Exception;

    /**
     * Throws a {@link FallthroughException} in order to try to convert {@code result} to
     * {@link HttpResponse} by the next converter.
     */
    static <T> T fallthrough() {
        // Always throw the exception quietly.
        throw FallthroughException.get();
    }
}
