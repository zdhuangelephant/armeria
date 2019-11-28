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

package com.linecorp.armeria.server.healthcheck;

import com.linecorp.armeria.server.Server;

/**
 * Determines whether the {@link Server} is healthy. All registered {@link HealthChecker}s must return
 * {@code true} for the {@link Server} to be considered healthy.
 * <br/>
 * 决定Server是否健康； 所有已经注册的{@link HealthChecker}s必需返回true
 */
@FunctionalInterface
public interface HealthChecker {
    /**
     * Returns {@code true} if and only if the {@link Server} is healthy.
     * <br/>
     * 只有Server是在健康的状况下， 该方法会返回true
     */
    boolean isHealthy();
}
