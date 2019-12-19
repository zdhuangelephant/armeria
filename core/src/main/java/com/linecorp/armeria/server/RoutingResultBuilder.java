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

package com.linecorp.armeria.server;

import static com.linecorp.armeria.server.RoutingResult.LOWEST_SCORE;
import static java.util.Objects.requireNonNull;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.internal.ArmeriaHttpUtil;

/**
 * Builds a new {@link RoutingResult}.
 * 构建一个{@link RoutingResult}的Builder工具类
 */
public final class RoutingResultBuilder {
    // 匹配的请求路径
    @Nullable
    private String path;

    // 请求参数
    @Nullable
    private String query;

    // 路径参数集合
    private final ImmutableMap.Builder<String, String> pathParams = ImmutableMap.builder();

    // 默认最低分
    private int score = LOWEST_SCORE;

    // 待商量的响应Mime类型
    @Nullable
    private MediaType negotiatedResponseMediaType;

    /**
     * Sets the mapped path, encoded as defined in <a href="https://tools.ietf.org/html/rfc3986">RFC3986</a>.
     */
    public RoutingResultBuilder path(String path) {
        this.path = requireNonNull(path, "path");
        return this;
    }

    /**
     * Sets the specified query.
     */
    public RoutingResultBuilder query(@Nullable String query) {
        this.query = query;
        return this;
    }

    /**
     * Adds a decoded path parameter.
     */
    public RoutingResultBuilder decodedParam(String name, String value) {
        pathParams.put(requireNonNull(name, "name"), requireNonNull(value, "value"));
        return this;
    }

    /**
     * Adds an encoded path parameter, which will be decoded in UTF-8 automatically.
     * 添加一个编码过的路径参数，它将自动的以UTF-8的解码方式进行解码
     */
    public RoutingResultBuilder rawParam(String name, String value) {
        pathParams.put(requireNonNull(name, "name"),
                       ArmeriaHttpUtil.decodePath(requireNonNull(value, "value")));
        return this;
    }

    /**
     * Sets the score.
     */
    public RoutingResultBuilder score(int score) {
        this.score = score;
        return this;
    }

    /**
     * Sets the negotiated producible {@link MediaType}.
     */
    public RoutingResultBuilder negotiatedResponseMediaType(MediaType negotiatedResponseMediaType) {
        this.negotiatedResponseMediaType = requireNonNull(negotiatedResponseMediaType,
                                                          "negotiatedResponseMediaType");
        return this;
    }

    /**
     * Returns a newly-created {@link RoutingResult}.
     * <br/>
     * 返回一个新创建的{@link RoutingResult}
     */
    public RoutingResult build() {
        if (path == null) {
            return RoutingResult.empty();
        }

        return new RoutingResult(path, query, pathParams.build(), score, negotiatedResponseMediaType);
    }
}
