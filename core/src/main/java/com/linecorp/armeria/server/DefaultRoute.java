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

import static com.linecorp.armeria.server.RoutingResult.HIGHEST_SCORE;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;

/**
 * Route的默认实现类
 * 该类中的{@link DefaultRoute#apply(RoutingContext)}方法是核心方法， 其内部大致做了以下几件事:
 * <ul>
 *     <li>根据routingCtx看能否匹配到可用的{@link RoutingResultBuilder}，若不可用则返回Empty</li>
 *     <li>其次检测是否跨域请求，如果不是的话，并对不支持的{@link HttpMethod}做405处理、并返回Empty</li>
 *     <li>接着对routingCtx的content-Type进行校验</li>
 *     <li>接着对routingCtx的Accept头数据进行校验，分别为当routingCtx携带Accept头，和routingCtx不携带Accept头，分别进行比对，是否合法校验的处理</li>
 * </ul>
 */
final class DefaultRoute implements Route {

    // 日志连接符
    private static final Joiner loggerNameJoiner = Joiner.on('_');
    // 仪表tag连接符
    private static final Joiner meterTagJoiner = Joiner.on(',');

    /**
     * 三剑客
     * methods、consumes、produces
     */
    private final PathMapping pathMapping;
    private final Set<HttpMethod> methods;
    private final Set<MediaType> consumes;  // 客户端的Content-Type头
    private final Set<MediaType> produces;  // 客户端的Accept头[包括但不限于Accept、Accept-Encoding、Accept-Language]的数组数据

    private final String loggerName;
    private final String meterTag;

    // 复杂度
    private final int complexity;

    DefaultRoute(PathMapping pathMapping, Set<HttpMethod> methods,
                 Set<MediaType> consumes, Set<MediaType> produces) {
        this.pathMapping = requireNonNull(pathMapping, "pathMapping");
        this.methods = Sets.immutableEnumSet(requireNonNull(methods, "methods"));
        this.consumes = ImmutableSet.copyOf(requireNonNull(consumes, "consumes"));
        this.produces = ImmutableSet.copyOf(requireNonNull(produces, "produces"));

        loggerName = generateLoggerName(pathMapping.loggerName(), methods, consumes, produces);

        meterTag = generateMeterTag(pathMapping.meterTag(), methods, consumes, produces);

        //
        int complexity = 0;
        if (!methods.isEmpty()) {
            complexity++;
        }

        if (!consumes.isEmpty()) {
            complexity += 2;
        }
        if (!produces.isEmpty()) {
            complexity += 4;
        }
        this.complexity = complexity;
    }

    // 此方法是核心方法，根据参数RoutingContext来匹配获取一个Service对象【Service对象被RoutingResult包装】
    @Override
    public RoutingResult apply(RoutingContext routingCtx) {
        final RoutingResultBuilder builder = pathMapping.apply(requireNonNull(routingCtx, "routingCtx"));
        if (builder == null) {
            return RoutingResult.empty();
        }

        if (methods.isEmpty()) {
            return builder.build();
        }

        /*
         * 我们需要在检查路径以后，也要对请求方法进行检查，看看是否支持客户端的请求方法。为了返回'405 Method Not Allowed'。
         * 如果我们的请求是一个跨域前置请求，我们不关心映射到的路径是否支持OPTIONS方法，因为请求总会被传递进目的服务内。
         * 大部分的场景下，跨域前置请求在到达目的Service之前，总会会被CorsService处理掉，
         */
        // We need to check the method after checking the path, in order to return '405 Method Not Allowed'.
        // If the request is a CORS preflight, we don't care whether the path mapping supports OPTIONS method.
        // The request may be always passed into the designated service, but most of cases, it will be handled
        // by a CorsService decorator before it reaches the final service.
        if (!routingCtx.isCorsPreflight() && !methods.contains(routingCtx.method())) {
            /*
             * '415 Unsupported Media Type' 和 '406 Not Acceptable' 相对于'405 Method Not Allowed'是更具体的http状态错误提示
             *  所以如果前面没有设置状态码的话，在这里就需要笼统的设置为'405 Method Not Allowed'了。
             */
            // '415 Unsupported Media Type' and '406 Not Acceptable' is more specific than
            // '405 Method Not Allowed'. So 405 would be set if there is no status code set before.
            if (!routingCtx.delayedThrowable().isPresent()) {
                routingCtx.delayThrowable(HttpStatusException.of(HttpStatus.METHOD_NOT_ALLOWED));
            }
            return RoutingResult.empty();
        }

        final MediaType contentType = routingCtx.contentType();
        boolean contentTypeMatched = false;
        if (contentType == null) {
            if (consumes().isEmpty()) {
                contentTypeMatched = true;
            }
        } else if (!consumes().isEmpty()) {
            for (MediaType consumeType : consumes) {
                contentTypeMatched = contentType.belongsTo(consumeType);
                if (contentTypeMatched) {
                    break;
                }
            }
            if (!contentTypeMatched) {
                routingCtx.delayThrowable(HttpStatusException.of(HttpStatus.UNSUPPORTED_MEDIA_TYPE));
                return RoutingResult.empty();
            }
        }

        final List<MediaType> acceptTypes = routingCtx.acceptTypes();
        if (acceptTypes.isEmpty()) {
            if (contentTypeMatched && produces().isEmpty()) {
                builder.score(HIGHEST_SCORE);
            }
            for (MediaType produceType : produces) {
                if (!isAnyType(produceType)) {
                    return builder.negotiatedResponseMediaType(produceType).build();
                }
            }
            return builder.build();
        }

        if (!produces.isEmpty()) {
            for (MediaType produceType : produces) {
                for (int i = 0; i < acceptTypes.size(); i++) {
                    final MediaType acceptType = acceptTypes.get(i);
                    if (produceType.belongsTo(acceptType)) {
                        /*
                         * 为了提早停止O(MN)的遍历，我们设置了当第一个routingCtx#acceptTypes()第一个匹配的时候，得分最高。
                         * 因为当第一个不匹配的的时候，倘若执着在进行下一个的匹配，是没有任何意义的。
                         */
                        // To early stop path mapping traversal,
                        // we set the score as the best score when the index is 0.

                        final int score = i == 0 ? HIGHEST_SCORE : -1 * i;
                        builder.score(score);
                        if (!isAnyType(produceType)) {
                            return builder.negotiatedResponseMediaType(produceType).build();
                        }
                        return builder.build();
                    }
                }
            }
            // 代码执行到这里的话， 说明routingCtx.acceptTypes()的值一个都没有和当前Route内的produces 相匹配。
            routingCtx.delayThrowable(HttpStatusException.of(HttpStatus.NOT_ACCEPTABLE));
            return RoutingResult.empty();
        }

        return builder.build();
    }

    private static boolean isAnyType(MediaType contentType) {
        // Ignores all parameters including the quality factor.
        return "*".equals(contentType.type()) || "*".equals(contentType.subtype());
    }

    @Override
    public Set<String> paramNames() {
        return pathMapping.paramNames();
    }

    @Override
    public String loggerName() {
        return loggerName;
    }

    @Override
    public String meterTag() {
        return meterTag;
    }

    @Override
    public RoutePathType pathType() {
        return pathMapping.pathType();
    }

    @Override
    public List<String> paths() {
        return pathMapping.paths();
    }

    @Override
    public int complexity() {
        return complexity;
    }

    @Override
    public Set<HttpMethod> methods() {
        return methods;
    }

    @Override
    public Set<MediaType> consumes() {
        return consumes;
    }

    @Override
    public Set<MediaType> produces() {
        return produces;
    }

    private static String generateLoggerName(String prefix, Set<HttpMethod> methods,
                                             Set<MediaType> consumes, Set<MediaType> produces) {
        final StringJoiner name = new StringJoiner(".");
        name.add(prefix);
        if (!methods.isEmpty()) {
            name.add(loggerNameJoiner.join(methods.stream().sorted().iterator()));
        }

        // The following three cases should be different to each other.
        // Each name would be produced as follows:
        //
        // consumes: text/plain, text/html               -> consumes.text_plain.text_html
        // consumes: text/plain, produces: text/html -> consumes.text_plain.produces.text_html
        // produces: text/plain, text/html               -> produces.text_plain.text_html

        if (!consumes.isEmpty()) {
            name.add("consumes");
            consumes.forEach(e -> name.add(e.type() + '_' + e.subtype()));
        }
        if (!produces.isEmpty()) {
            name.add("produces");
            produces.forEach(e -> name.add(e.type() + '_' + e.subtype()));
        }
        return name.toString();
    }

    private static String generateMeterTag(String parentTag, Set<HttpMethod> methods,
                                           Set<MediaType> consumes, Set<MediaType> produces) {

        final StringJoiner name = new StringJoiner(",");
        name.add(parentTag);
        if (!methods.isEmpty()) {
            name.add("methods:" + meterTagJoiner.join(methods.stream().sorted().iterator()));
        }

        // The following three cases should be different to each other.
        // Each name would be produced as follows:
        //
        // consumes: text/plain, text/html               -> "consumes:text/plain,text/html"
        // consumes: text/plain, produces: text/html -> "consumes:text/plain,produces:text/html"
        // produces: text/plain, text/html               -> "produces:text/plain,text/html"

        addMediaTypes(name, "consumes", consumes);
        addMediaTypes(name, "produces", produces);

        return name.toString();
    }

    private static void addMediaTypes(StringJoiner builder, String prefix, Set<MediaType> mediaTypes) {
        if (!mediaTypes.isEmpty()) {
            final StringBuilder buf = new StringBuilder();
            buf.append(prefix).append(':');
            for (MediaType t : mediaTypes) {
                buf.append(t.type());
                buf.append('/');
                buf.append(t.subtype());
                buf.append(',');
            }
            buf.setLength(buf.length() - 1);
            builder.add(buf.toString());
        }
    }

    @Override
    public int hashCode() {
        return meterTag.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof DefaultRoute)) {
            return false;
        }

        final DefaultRoute that = (DefaultRoute) o;
        return Objects.equals(pathMapping, that.pathMapping) &&
               methods.equals(that.methods) &&
               consumes.equals(that.consumes) &&
               produces.equals(that.produces);
    }

    @Override
    public String toString() {
        return meterTag;
    }
}
