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
package com.linecorp.armeria.server;

import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;

/**
 * Matches the absolute path part of a URI and extracts path parameters from it.
 *
 * NOTE: 请求路径的提取抽象类，包括精准匹配，正则匹配等多种。并且承担着提取路径参数的作用
 */
interface PathMapping {

    /**
     * Matches the specified {@code path} and extracts the path parameters from it.
     * 匹配指定的path，并且提取请求路径的参数
     *
     * @param routingCtx a context to find the {@link Service}.  一个用来寻找{@link Service}的上下文
     *
     * @return a non-empty {@link RoutingResultBuilder} if the specified {@code path} matches this mapping.
     *         {@code null} otherwise.  如果指定的path与这个PathMapping相匹配，一个非空的{@link RoutingResultBuilder}的将会被返回，否则返回null
     */
    @Nullable
    RoutingResultBuilder apply(RoutingContext routingCtx);

    /**
     * Returns the names of the path parameters extracted by this mapping.
     * 返回被此{@link PathMapping}实例提取的路径参数
     */
    Set<String> paramNames();

    /**
     * Returns the logger name. 返回日志的名字
     *
     * @return the logger name whose components are separated by a dot (.)
     */
    String loggerName();

    /**
     * Returns the value of the {@link Tag} in a {@link Meter} of this {@link PathMapping}.
     * 返回此{@link PathMapping}的在{@link Meter}内的{@link Tag}值
     */
    String meterTag();

    /**
     * Returns the type of the path which was specified when this is created.
     * <br/>
     * 当{@link PathMapping}实例被创建的时候， 返回被指定路径的类型
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
     * {@link RoutePathType#REGEX} has only one path that represents it. e.g, {@code [ "^/(?<foo>.*)$" ]}
     *
     * <p>{@link RoutePathType#REGEX_WITH_PREFIX} has two paths. The first one is the prefix and the second
     * one is the regex. e.g, {@code [ "/bar/", "^/(?<foo>.*)$" ]}
     */
    List<String> paths();
}
