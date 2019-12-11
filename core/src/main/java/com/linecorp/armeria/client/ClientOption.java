/*
 * Copyright 2015 LINE Corporation
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

import java.util.function.Function;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.ContentPreviewerFactory;
import com.linecorp.armeria.common.util.AbstractOption;

import io.netty.util.ConstantPool;

/**
 * A client option.
 * 客户端的选项
 * (id, name) -> obj
 *
 * @param <T> the type of the option value 选项泛型的类型
 */
public final class ClientOption<T> extends AbstractOption<T> {

    @SuppressWarnings("rawtypes")
    private static final ConstantPool pool = new ConstantPool() {
        @Override
        protected ClientOption<Object> newConstant(int id, String name) {
            return new ClientOption<>(id, name);
        }
    };

    /**
     * The timeout of a socket write.
     */
    public static final ClientOption<Long> WRITE_TIMEOUT_MILLIS = valueOf("WRITE_TIMEOUT_MILLIS");

    /**
     * The timeout of a socket write.
     *
     * @deprecated Use {@link #WRITE_TIMEOUT_MILLIS}.
     */
    @Deprecated
    public static final ClientOption<Long> DEFAULT_WRITE_TIMEOUT_MILLIS = WRITE_TIMEOUT_MILLIS;

    /**
     * The timeout of a server reply to a client call.
     */
    public static final ClientOption<Long> RESPONSE_TIMEOUT_MILLIS = valueOf("RESPONSE_TIMEOUT_MILLIS");

    /**
     * The timeout of a server reply to a client call.
     *
     * @deprecated Use {@link #RESPONSE_TIMEOUT_MILLIS}.
     */
    @Deprecated
    public static final ClientOption<Long> DEFAULT_RESPONSE_TIMEOUT_MILLIS = RESPONSE_TIMEOUT_MILLIS;

    /**
     * The maximum allowed length of a server response.
     */
    public static final ClientOption<Long> MAX_RESPONSE_LENGTH = valueOf("DEFAULT_MAX_RESPONSE_LENGTH");

    /**
     * The maximum allowed length of a server response.
     *
     * @deprecated Use {@link #MAX_RESPONSE_LENGTH};
     */
    @Deprecated
    public static final ClientOption<Long> DEFAULT_MAX_RESPONSE_LENGTH = MAX_RESPONSE_LENGTH;

    /**
     * The additional HTTP headers to send with requests. Used only when the underlying
     * {@link SessionProtocol} is HTTP.
     */
    public static final ClientOption<HttpHeaders> HTTP_HEADERS = valueOf("HTTP_HEADERS");

    /**
     * The {@link Function} that decorates the client components.
     */
    public static final ClientOption<ClientDecoration> DECORATION = valueOf("DECORATION");

    public static final ClientOption<ContentPreviewerFactory> REQ_CONTENT_PREVIEWER_FACTORY = valueOf(
            "REQ_CONTENT_PREVIEWER_FACTORY");

    public static final ClientOption<ContentPreviewerFactory> RES_CONTENT_PREVIEWER_FACTORY = valueOf(
            "RES_CONTENT_PREVIEWER_FACTORY");

    /**
     * Returns the {@link ClientOption} of the specified name.
     * <br/>
     * 返回指定name对应的值
     */
    @SuppressWarnings("unchecked")
    public static <T> ClientOption<T> valueOf(String name) {
        return (ClientOption<T>) pool.valueOf(name);
    }

    /**
     * Creates a new {@link ClientOption} of the specified unique {@code name}.
     * <p/>提供ConstantPool#newConstant(id, name)的构造方法，借助于Netty的构建方法。学以致用，以后可以放到自己的常量配置池内。
     */
    private ClientOption(int id, String name) {
        super(id, name);
    }

    /**
     * Creates a new value of this option.
     * <p/>创建ClientOptionValue实例,key为当前ClientOptionValue。
     *     其实这个方法应该是放在ClientOptionValue内最好了。之后就变成了
     */
    public ClientOptionValue<T> newValue(T value) {
        requireNonNull(value, "value");
        return new ClientOptionValue<>(this, value);
    }
}
