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

import static com.linecorp.armeria.internal.RouteUtil.newLoggerName;
import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

/**
 * The default {@link PathMapping} implementation. It holds three things:
 * <ul>
 *   <li>The regex-compiled form of the path. It is used for matching and extracting. 用以匹配和提取</li>
 *   <li>The skeleton of the path. It is used for duplication detecting. 用以重复检测</li>
 *   <li>A set of path parameters declared in the path pattern. 在路径模式，路径参数集合</li>
 * </ul>
 *
 * <p>默认的{@link PathMapping}实现，它持有三件事</p>
 * <p></p>
 */
final class ParameterizedPathMapping extends AbstractPathMapping {

    // 有效路径字符
    private static final Pattern VALID_PATTERN = Pattern.compile("(/[^/{}:]+|/:[^/{}]+|/\\{[^/{}]+})+/?");

    // 空名字
    private static final String[] EMPTY_NAMES = new String[0];

    // 路径分隔符
    private static final Splitter PATH_SPLITTER = Splitter.on('/');

    /**
     * The original path pattern specified in the constructor.
     * <p>在构造器内指定的原始路径</p>
     * <p>/service/{value}/test/:value2/something/{value3}</p>
     */
    private final String pathPattern;

    /**
     * Regex form of given path, which will be used for matching or extracting.
     * <p>正则规则， 将用来匹配或提取操作</p>
     *
     * <p>e.g. "/{x}/{y}/{x}" -> "/(?&lt;x&gt;[^/]+)/(?&lt;y&gt;[^/]+)/(\\k&lt;x&gt;)"
     * <p>/service/([^/]+)/test/([^/]+)/something/([^/]+)</p>
     */
    private final Pattern pattern;

    /**
     * Skeletal form of given path, which is used for duplicated routing rule detection.
     * For example, "/{a}/{b}" and "/{c}/{d}" has same skeletal form and regarded as duplicated.
     * 请求路径的基本骨架，其用来用作发觉是否重复路由。
     * <p>eg "/{a}/{b}" and "/{c}/{d}" 两者有着相同的基本组成形式，则将其视为重复的结构。</p>
     * <p>e.g. "/{x}/{y}/{z}" -> "/:/:/:"  这两个也是有着相同的基本组成形式。
     * <p>/service/:/test/:/something/:</p>
     */
    private final String skeleton;

    // 路径容器
    // /service/:/test/:/something/:, /service/:/test/:/something/:
    private final List<String> paths;

    /**
     * The names of the path parameters in the order of appearance.
     * <p> 路径中参数的名字，有序排列
     * <p>["value", "value2", "value3"]
     */
    private final String[] paramNameArray;

    /**
     * The names of the path parameters this mapping will extract.
     * <p>["value", "value2", "value3"]
     */
    private final Set<String> paramNames;

    // 日志名字
    private final String loggerName;

    /**
     * Create a {@link ParameterizedPathMapping} instance from given {@code pathPattern}.
     * <p>创建一个{@link ParameterizedPathMapping}实例，从传入的{@code pathPattern}。
     *
     * @param pathPattern the {@link String} that contains path params.  pathPattern包含了路径参数 如下:
     *             e.g. {@code /users/{name}} or {@code /users/:name}
     *
     * @throws IllegalArgumentException if the {@code pathPattern} is invalid. 如果传入的pathPattern是非法的，则抛出异常。
     */
    ParameterizedPathMapping(String pathPattern) {
        requireNonNull(pathPattern, "pathPattern");

        if (!pathPattern.startsWith("/")) {
            throw new IllegalArgumentException("pathPattern: " + pathPattern + " (must start with '/')");
        }

        if (!VALID_PATTERN.matcher(pathPattern).matches()) {
            throw new IllegalArgumentException("pathPattern: " + pathPattern + " (invalid pattern)");
        }

        final StringJoiner patternJoiner = new StringJoiner("/");
        final StringJoiner skeletonJoiner = new StringJoiner("/");
        final List<String> paramNames = new ArrayList<>();
        for (String token : PATH_SPLITTER.split(pathPattern)) {
            // 拿到参数名
            final String paramName = paramName(token);
            if (paramName == null) {
                // If the given token is a constant, do not manipulate it.
                patternJoiner.add(token);
                skeletonJoiner.add(token);
                continue;
            }

            final int paramNameIdx = paramNames.indexOf(paramName);
            if (paramNameIdx < 0) {
                // If the given token appeared first time, add it to the set and
                // replace it with a capturing group expression in regex.
                paramNames.add(paramName);
                patternJoiner.add("([^/]+)");
            } else {
                // If the given token appeared before, replace it with a back-reference expression
                // in regex.
                patternJoiner.add("\\" + (paramNameIdx + 1));
            }
            skeletonJoiner.add(":");
        }

        this.pathPattern = pathPattern;
        pattern = Pattern.compile(patternJoiner.toString());
        skeleton = skeletonJoiner.toString();
        paths = ImmutableList.of(skeleton, skeleton);
        paramNameArray = paramNames.toArray(EMPTY_NAMES);
        this.paramNames = ImmutableSet.copyOf(paramNames);

        loggerName = newLoggerName(pathPattern);
    }

    /**
     * Returns the name of the path parameter contained in the path element. If it contains no path parameter,
     * {@code null} is returned. e.g.
     * <ul>
     *   <li>{@code "{foo}"} -> {@code "foo"}</li>
     *   <li>{@code ":bar"} -> {@code "bar"}</li>
     *   <li>{@code "baz"} -> {@code null}</li>
     * </ul>
     * <p>返回包含着的路径参数的参数名， 示例如上所示:</p>
     */
    @Nullable
    private static String paramName(String token) {
        if (token.startsWith("{") && token.endsWith("}")) {
            return token.substring(1, token.length() - 1);
        }

        if (token.startsWith(":")) {
            return token.substring(1);
        }

        return null;
    }

    /**
     * Returns the skeleton.
     */
    String skeleton() {
        return skeleton;
    }

    @Override
    public Set<String> paramNames() {
        return paramNames;
    }

    @Override
    public String loggerName() {
        return loggerName;
    }

    @Override
    public String meterTag() {
        return pathPattern;
    }

    @Override
    public RoutePathType pathType() {
        return RoutePathType.PARAMETERIZED;
    }

    @Override
    public List<String> paths() {
        return paths;
    }

    @Nullable
    @Override
    RoutingResultBuilder doApply(RoutingContext routingCtx) {
        // TODO 真正的routingCtx和pattern的匹配从这开始算起！！
        // /service/([^/]+)/test/([^/]+)/something/([^/]+) 和 routingCtx内的path进行正则匹配
        final Matcher matcher = pattern.matcher(routingCtx.path());
        // 根据匹配结果，匹配不到的话，则返回null
        if (!matcher.matches()) {
            return null;
        }

        // 如果匹配成功了，则返回RoutingResultBuilder
        final RoutingResultBuilder builder = RoutingResult.builder()
                                                          .path(routingCtx.path())
                                                          .query(routingCtx.query());
        // 从单元测试内， 看出paramNameArray为["value", "value2", "value3"]
        for (int i = 0; i < paramNameArray.length; i++) {
            builder.rawParam(paramNameArray[i], matcher.group(i + 1));
        }
        return builder;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ParameterizedPathMapping)) {
            return false;
        }

        final ParameterizedPathMapping that = (ParameterizedPathMapping) o;

        return skeleton.equals(that.skeleton) &&
               Arrays.equals(paramNameArray, that.paramNameArray);
    }

    @Override
    public int hashCode() {
        return skeleton.hashCode() * 31 + Arrays.hashCode(paramNameArray);
    }

    @Override
    public String toString() {
        return pathPattern;
    }
}
