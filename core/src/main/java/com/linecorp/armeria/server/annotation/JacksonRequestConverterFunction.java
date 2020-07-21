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

import static java.util.Objects.requireNonNull;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * A default implementation of a {@link RequestConverterFunction} which converts a JSON body of
 * the {@link AggregatedHttpRequest} to an object by {@link ObjectMapper}.
 *
 * <br/>
 * <h2>接口的默认实现实现子类，就是把参数转换成json格式;</h2>
 * <br/>
 *  {@link RequestConverterFunction}的具体实现子类，将req的内容转换为Json
 *
 * <br/>
 * <pre>{@code
 * public class MyAnnotatedService {
 *
 *     // JacksonRequestConverterFunction will work for the content type of 'application/json' or
 *     // one of '+json' types.
 *     @Post("/hello1")
 *     public HttpResponse hello1(JsonNode body) { ... }
 *
 *     @Post("/hello2")
 *     public HttpResponse hello2(MyJsonRequest body) { ... }
 *
 *     // StringRequestConverterFunction will work regardless of the content type.
 *     @Post("/hello3")
 *     public HttpResponse hello3(String body) { ... }
 *
 *     @Post("/hello4")
 *     public HttpResponse hello4(CharSequence body) { ... }
 *
 *     // ByteArrayRequestConverterFunction will work regardless of the content type.
 *     @Post("/hello5")
 *     public HttpResponse hello5(byte[] body) { ... }
 *
 *     @Post("/hello6")
 *     public HttpResponse hello6(HttpData body) { ... }
 * }
 * }</pre>
 */
public class JacksonRequestConverterFunction implements RequestConverterFunction {

    private static final ObjectMapper defaultObjectMapper = new ObjectMapper();

    private final ObjectMapper mapper;
    // 内部缓存为了，提高性能，相同类型的二次获取，可以直接从内存中得到
    private final ConcurrentMap<Class<?>, ObjectReader> readers = new ConcurrentHashMap<>();

    /**
     * Creates an instance with the default {@link ObjectMapper}.
     * 通过默认的json转换器，创造是一个ConverterFunction实例
     */
    public JacksonRequestConverterFunction() {
        this(defaultObjectMapper);
    }

    /**
     * Creates an instance with the specified {@link ObjectMapper}.
     */
    public JacksonRequestConverterFunction(ObjectMapper mapper) {
        this.mapper = requireNonNull(mapper, "mapper");
    }

    /**
     * Converts the specified {@link AggregatedHttpRequest} to an object of {@code expectedResultType}.
     * <br/>
     * 把{@link AggregatedHttpRequest}转变成一个期望类型
     */
    @Override
    @Nullable
    public Object convertRequest(ServiceRequestContext ctx, AggregatedHttpRequest request,
                                 Class<?> expectedResultType) throws Exception {
        final MediaType contentType = request.contentType();
        if (contentType != null && (contentType.is(MediaType.JSON) ||
                                    contentType.subtype().endsWith("+json"))) {
            final ObjectReader reader = readers.computeIfAbsent(expectedResultType, mapper::readerFor);
            if (reader != null) {
                final String content = request.content(contentType.charset().orElse(StandardCharsets.UTF_8));
                try {
                    return reader.readValue(content);
                } catch (JsonProcessingException e) {
                    if (expectedResultType == byte[].class ||
                        expectedResultType == HttpData.class ||
                        expectedResultType == String.class ||
                        expectedResultType == CharSequence.class) {
                        return RequestConverterFunction.fallthrough();
                    }

                    throw new IllegalArgumentException("failed to parse a JSON document: " + e, e);
                }
            }
        }
        return RequestConverterFunction.fallthrough();
    }
}
