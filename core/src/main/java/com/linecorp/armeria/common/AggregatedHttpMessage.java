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

package com.linecorp.armeria.common;

import java.nio.charset.Charset;

import javax.annotation.Nullable;

/**
 * A complete HTTP message whose content is readily available as a single {@link HttpData}. It can be an
 * HTTP request or an HTTP response depending on what header values it contains. For example, having a
 * {@link HttpHeaderNames#STATUS} header could mean it is an HTTP response.
 * <br/>
 * 一个完整的HTTP message 其内响应内容作为单独的{@link HttpData}是可用的。依赖于响应头的值，它可能是一个请求也可能是一个响应。eg:
 * 头部信息有着{@link HttpHeaderNames#STATUS}就说明是一个响应报文。
 */
interface AggregatedHttpMessage {

    /**
     * Returns the HTTP headers.
     */
    HttpHeaders headers();

    /**
     * Returns the HTTP trailers.
     *
     * @deprecated Use {@link #trailers()}.
     */
    @Deprecated
    default HttpHeaders trailingHeaders() {
        return trailers();
    }

    /**
     * Returns the HTTP trailers.
     */
    HttpHeaders trailers();

    /**
     * Returns the content of this message.
     */
    HttpData content();

    /**
     * Returns the content of this message as a string encoded in the specified {@link Charset}.
     */
    default String content(Charset charset) {
        return content().toString(charset);
    }

    /**
     * Returns the content of this message as a UTF-8 string.
     */
    default String contentUtf8() {
        return content().toStringUtf8();
    }

    /**
     * Returns the content of this message as an ASCII string.
     */
    default String contentAscii() {
        return content().toStringAscii();
    }

    /**
     * Returns the value of the {@code 'content-type'} header.
     * @return the valid header value if present. {@code null} otherwise.
     */
    @Nullable
    default MediaType contentType() {
        return headers().contentType();
    }
}
