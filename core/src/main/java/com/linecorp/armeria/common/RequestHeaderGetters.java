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
package com.linecorp.armeria.common;

import javax.annotation.Nullable;

/**
 * Provides the getter methods to {@link RequestHeaders} and {@link RequestHeadersBuilder}.
 *
 * @see ResponseHeaderGetters
 */
interface RequestHeaderGetters extends HttpHeaderGetters {

    /**
     * Returns the value of the {@code ":method"} header as an {@link HttpMethod}.
     * {@link HttpMethod#UNKNOWN} is returned if the value is not defined in {@link HttpMethod}.
     * <br/>
     * 返回{@code ":method"}的头对应的{@link HttpMethod}的值。如果没有则返回{@link HttpMethod#UNKNOWN}
     *
     * @throws IllegalStateException if there is no such header.
     */
    HttpMethod method();

    /**
     * Returns the value of the {@code ":path"} header.
     * <br/>
     * 返回{@code ":path"}对应的值。
     *
     * @throws IllegalStateException if there is no such header.
     */
    String path();

    /**
     * Returns the value of the {@code ":scheme"} header or {@code null} if there is no such header.
     * <br/>
     * 返回{@code ":scheme"}对应的值。协议Https/Http
     */
    @Nullable
    String scheme();

    /**
     * Returns the value of the {@code ":authority"} header or {@code null} if there is no such header.
     * <br/>
     * 返回{@code ":authority"}对应的值。127.0.0.1:8080
     */
    @Nullable
    String authority();
}
