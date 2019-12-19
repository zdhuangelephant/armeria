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

import static com.linecorp.armeria.internal.RouteUtil.UNKNOWN_LOGGER_NAME;
import static com.linecorp.armeria.internal.RouteUtil.ensureAbsolutePath;
import static java.util.Objects.requireNonNull;

import javax.annotation.Nullable;

/**
 * A skeletal {@link PathMapping} implementation. Implement {@link #doApply(RoutingContext)}.
 * PathMapping的抽象骨架实现类。所以的子类需要实现{@link #doApply(RoutingContext)}方法， 来实现各自不同的"路径提取"方式。
 */
abstract class AbstractPathMapping implements PathMapping {

    /**
     * {@inheritDoc} This method performs sanity checks on the specified {@code path} and calls
     * {@link #doApply(RoutingContext)}.
     * <br/>
     * 这个方法对指定的path做了一些前置检查，然后调用{@link #doApply(RoutingContext)}.
     */
    @Nullable
    @Override
    public final RoutingResultBuilder apply(RoutingContext routingCtx) {
        ensureAbsolutePath(requireNonNull(routingCtx, "routingCtx").path(), "path");
        return doApply(routingCtx);
    }

    /**
     * Invoked by {@link #apply(RoutingContext)} to perform the actual path matching and path parameter
     * extraction.
     * <p>这个方法才是真正的干活的！ 被{@link #apply(RoutingContext)}方法调用，来执行路径匹配和路径参数提取</p>
     *
     * @param routingCtx a context to find the {@link Service}
     *
     * @return a non-empty {@link RoutingResultBuilder} if the specified {@code path} matches this mapping.
     *         {@code null} otherwise.
     */
    @Nullable
    abstract RoutingResultBuilder doApply(RoutingContext routingCtx);

    @Override
    public String loggerName() {
        return UNKNOWN_LOGGER_NAME;
    }

    @Override
    public String meterTag() {
        return "__UNKNOWN_PATH__";
    }
}
