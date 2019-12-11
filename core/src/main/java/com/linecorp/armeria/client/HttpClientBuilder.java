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

package com.linecorp.armeria.client;

import static java.util.Objects.requireNonNull;

import java.net.URI;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;

/**
 * Creates a new HTTP client that connects to the specified {@link URI} using the builder pattern.
 * Use the factory methods in {@link HttpClient} if you do not have many options to override.
 * Please refer to {@link ClientBuilder} for how decorators and HTTP headers are configured
 *
 * 通过Builder模式创建一个可以连接到指定URI的Http client客户端。
 * 如果你没有足够的options覆盖的话，将会采用{@link HttpClient}中的工厂
 * 请参考于{@link ClientBuilder}，看如何配置decorators和HttpHeaders
 */
public final class HttpClientBuilder extends AbstractClientOptionsBuilder<HttpClientBuilder> {

    /**
     * An undefined {@link URI} to create {@link HttpClient} without specifying {@link URI}.
     * <p>声明一个NONE的URI，这是框架的常规写法，就算是None也要写。这是为了框架的健壮性和稳定性考虑。</p>
     */
    private static final URI UNDEFINED_URI = URI.create("none+http://undefined");

    /**
     * Returns {@code true} if the specified {@code uri} is an undefined {@link URI}.
     * 判断是否是NONE的URI
     */
    static boolean isUndefinedUri(URI uri) {
        return UNDEFINED_URI == uri;
    }

    // uri声明
    @Nullable
    private final URI uri;
    // Endpoint声明
    @Nullable
    private final Endpoint endpoint;
    // 协议声明 http/https
    @Nullable
    private final Scheme scheme;
    // 请求路径声明
    @Nullable
    private String path;
    // 默认的Client创建工厂
    private ClientFactory factory = ClientFactory.DEFAULT;

    /**
     * Creates a new instance.
     * 无效的Client
     */
    public HttpClientBuilder() {
        uri = UNDEFINED_URI;
        scheme = null;
        endpoint = null;
    }

    /**
     * Creates a new instance.
     *
     * @throws IllegalArgumentException if the scheme of the uri is not one of the fields
     *                                  in {@link SessionProtocol} or the uri violates RFC 2396
     */
    public HttpClientBuilder(String uri) {
        this(URI.create(requireNonNull(uri, "uri")));
    }

    /**
     * Creates a new instance.
     *
     * @throws IllegalArgumentException if the scheme of the uri is not one of the fields
     *                                  in {@link SessionProtocol}
     */
    public HttpClientBuilder(URI uri) {
        if (isUndefinedUri(uri)) {
            this.uri = uri;
        } else {
            validateScheme(requireNonNull(uri, "uri").getScheme());
            this.uri = URI.create(SerializationFormat.NONE + "+" + uri);
        }
        scheme = null;
        endpoint = null;
    }

    /**
     * Creates a new instance.
     *
     * @throws IllegalArgumentException if the {@code sessionProtocol} is not one of the fields
     *                                  in {@link SessionProtocol}
     */
    public HttpClientBuilder(SessionProtocol sessionProtocol, Endpoint endpoint) {
        validateScheme(requireNonNull(sessionProtocol, "sessionProtocol").uriText());

        uri = null;
        scheme = Scheme.of(SerializationFormat.NONE, sessionProtocol);
        this.endpoint = requireNonNull(endpoint, "endpoint");
    }

    private static void validateScheme(String scheme) {
        for (SessionProtocol p : SessionProtocol.values()) {
            if (scheme.equalsIgnoreCase(p.uriText())) {
                return;
            }
        }
        throw new IllegalArgumentException("scheme : " + scheme + " (expected: one of " +
                                           ImmutableList.copyOf(SessionProtocol.values()) + ')');
    }

    /**
     * Sets the {@link ClientFactory} of the client. The default is {@link ClientFactory#DEFAULT}.
     *
     */
    public HttpClientBuilder factory(ClientFactory factory) {
        this.factory = requireNonNull(factory, "factory");
        return this;
    }

    /**
     * Sets the {@code path} of the client.
     */
    public HttpClientBuilder path(String path) {
        this.path = requireNonNull(path, "path");
        return this;
    }

    /**
     * Returns a newly-created HTTP client based on the properties of this builder.
     *
     * @throws IllegalArgumentException if the scheme of the {@code uri} specified in
     *                                  {@link #HttpClientBuilder(String)} or {@link #HttpClientBuilder(URI)}
     *                                  is not an HTTP scheme  如果指定的schema 不是HTTP，则抛出异常
     */
    public HttpClient build() {
        if (uri != null) {
            return factory.newClient(uri, HttpClient.class, buildOptions());
        } else if (path != null) {
            return factory.newClient(scheme, endpoint, path, HttpClient.class, buildOptions());
        } else {
            return factory.newClient(scheme, endpoint, HttpClient.class, buildOptions());
        }
    }
}
