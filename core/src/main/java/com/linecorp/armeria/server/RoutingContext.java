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

package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Optional;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.internal.ArmeriaHttpUtil;

/**
 * Holds the parameters which are required to find a service available to handle the request.
 *
 *  NOTE: 根据参数获取能够处理某请求的相应的服务 2019-09-12 13:56:56
 */
public interface RoutingContext {

    /**
     * Returns the {@link VirtualHost} instance which belongs to this {@link RoutingContext}.
     *
     * NOTE: 获取该RoutingContext下的VirtualHost实例
     */
    VirtualHost virtualHost();

    /**
     * Returns the virtual host name of the request.
     *
     * NOTE: 获取某请求的virtualHost的hostname 2019-09-12 13:59:30
     */
    String hostname();

    /**
     * Returns {@link HttpMethod} of the request.
     *
     * NOTE: 获取该请求的请求方式 {@link HttpMethod}  2019-09-12 13:59:24
     */
    HttpMethod method();

    /**
     * Returns the absolute path retrieved from the request,
     * as defined in <a href="https://tools.ietf.org/html/rfc3986">RFC3986</a>.
     *
     * NOTE: 获取某请求的请求的全路径【即绝对路径】eg: http://127.0.0.1:8080/v1/user/list中的/v1/user/list    2019-09-12 14:00:55
     */
    String path();

    /**
     * Returns the query retrieved from the request,
     * as defined in <a href="https://tools.ietf.org/html/rfc3986">RFC3986</a>.
     *  NOTE: 获取该请求的请求参数    2019-09-12 14:02:46
     */
    @Nullable
    String query();

    /**
     * Returns {@link MediaType} specified by 'Content-Type' header of the request.
     *
     * NOTE: 获取请求头部的'Content-Type'的值
     */
    @Nullable
    MediaType contentType();

    /**
     * Returns a list of {@link MediaType}s that are specified in {@link HttpHeaderNames#ACCEPT} in the order
     * of client-side preferences. If the client does not send the header, this will contain only
     * {@link MediaType#ANY_TYPE}.
     *
     * NOTE: 获取Accept的所有值   2019-09-12 14:05:07
     */
    List<MediaType> acceptTypes();

    /**
     * Returns an identifier of this {@link RoutingContext} instance.
     * It would be used as a cache key to reduce pattern list traversal.
     *
     * NOTE: 获取该RoutingContext实例的唯一值，以缓存该值避免以后每次的重复遍历   2019-09-12 14:06:35
     * 该summary实际上就是以下5个指标的拼接
     *         0 : VirtualHost
     *         1 : HttpMethod
     *         2 : Path
     *         3 : Content-Type
     *         4 : Accept
     *
     */
    List<Object> summary();

    /**
     * Delays throwing a {@link Throwable} until reaching the end of the service list.
     *
     * NOTE:    遍历到ServiceList末尾号以后，便会抛出异常
     */
    void delayThrowable(Throwable cause);

    /**
     * Returns a delayed {@link Throwable} set before via {@link #delayThrowable(Throwable)}.
     *
     * NOTE:
     */
    Optional<Throwable> delayedThrowable();

    /**
     * Returns a wrapped {@link RoutingContext} which holds the specified {@code path}.
     * It is usually used to find a {@link Service} with a prefix-stripped path.
     *
     * NOTE: 返回一个RoutingContext的wrap对象。它一般通过path前缀来匹配一个可用的Service   2019-09-12 14:12:43
     */
    default RoutingContext overridePath(String path) {
        requireNonNull(path, "path");
        return new RoutingContextWrapper(this) {
            @Override
            public String path() {
                return path;
            }
        };
    }

    /**
     * Returns {@code true} if this context is for a CORS preflight request.
     * <p>检验当前的RouteContext是一个跨域的前置请求所产生的上下文</p>
     *
     * @see ArmeriaHttpUtil#isCorsPreflightRequest(HttpRequest)
     */
    boolean isCorsPreflight();
}
