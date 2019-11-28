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

import java.util.List;
import java.util.Set;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;

/**
 * {@link Route} maps from an incoming HTTP request to a {@link Service} based on its path, method,
 * content type and accepted types.
 * NOTE: 根据请求的path/method/contentType/acceptType来匹配一个合适的Service用来处理请求。
 */
public interface Route {

    /**
     * Returns a new builder.
     * NOTE: 获取一个RouteBuilder实例
     */
    static RouteBuilder builder() {
        return new RouteBuilder();
    }

    /**
     * Matches the specified {@link RoutingContext} and extracts the path parameters from it if exists.
     *
     * @param routingCtx a context to find the {@link Service}
     *
     * @return a non-empty {@link RoutingResult} if the {@linkplain RoutingContext#path() path},
     *         {@linkplain RoutingContext#method() method},
     *         {@linkplain RoutingContext#contentType() contentType} and
     *         {@linkplain RoutingContext#acceptTypes() acceptTypes} matches the equivalent conditions in
     *         {@link Route}. {@link RoutingResult#empty()} otherwise.
     *
     * @see RouteBuilder#methods(Iterable)
     * @see RouteBuilder#consumes(Iterable)
     * @see RouteBuilder#produces(Iterable)
     *
     *
     * NOTE: 根据RoutingContext匹配一个RoutingResult
     */
    RoutingResult apply(RoutingContext routingCtx);

    /**
     * Returns the names of the path parameters extracted by this mapping.
     *
     * NOTE: 获取参数集合
     */
    Set<String> paramNames();

    /**
     * Returns the logger name.
     *
     * @return the logger name whose components are separated by a dot (.)
     */
    String loggerName();

    /**
     * Returns the value of the {@link Tag} in a {@link Meter} of this {@link Route}.
     */
    String meterTag();

    /**
     * Returns the type of the path which was specified when this is created.
     *
     * NOTE: 获取请求path的类型
     */
    RoutePathType pathType();

    /**
     * Returns the list of paths that this {@link Route} has. The paths are different according to the value
     * of {@link #pathType()}. If the path type has a {@linkplain RoutePathType#hasTriePath() trie path},
     * this method will return a two-element list whose first element is the path that represents the type and
     * the second element is the trie path. {@link RoutePathType#EXACT}, {@link RoutePathType#PREFIX} and
     * {@link RoutePathType#PARAMETERIZED} have the trie path.
     *
     * <ul>
     *   <li>EXACT: {@code [ "/foo", "/foo" ]} (The trie path is the same.)</li>
     *   <li>PREFIX: {@code [ "/foo/", "/foo/*" ]}</li>
     *   <li>PARAMETERIZED: {@code [ "/foo/:", "/foo/:" ]} (The trie path is the same.)</li>
     * </ul>
     *
     * <p>{@link RoutePathType#REGEX} may have one or two paths. If the {@link Route} was created from a glob
     * pattern, it will have two paths where the first one is the regular expression and the second one
     * is the glob pattern, e.g. {@code [ "^/(?(.+)/)?foo$", "/*&#42;/foo" ]}.
     * If not created from a glob pattern, it will have only one path, which is the regular expression,
     * e.g, {@code [ "^/(?<foo>.*)$" ]}</p>
     *
     * <p>{@link RoutePathType#REGEX_WITH_PREFIX} has two paths. The first one is the regex and the second
     * one is the path. e.g, {@code [ "^/(?<foo>.*)$", "/bar/" ]}
     *
     * NOTE: 返回所有在Service中预定义的path的值
     */
    List<String> paths();

    /**
     * Returns the complexity of this {@link Route}. A higher complexity indicates more expensive computation
     * for route matching, usually due to additional number of checks.
     *
     * 返回该Route的复杂度[越复杂，指数就越高]
     */
    int complexity();

    /**
     * Returns the {@link Set} of {@link HttpMethod}s that this {@link Route} supports.
     */
    Set<HttpMethod> methods();

    /**
     * Returns the {@link Set} of {@link MediaType}s that this {@link Route} consumes.
     */
    Set<MediaType> consumes();

    /**
     * Returns the {@link Set} of {@link MediaType}s that this {@link Route} produces.
     */
    Set<MediaType> produces();
}
