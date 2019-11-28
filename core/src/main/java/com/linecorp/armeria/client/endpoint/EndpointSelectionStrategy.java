/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.client.endpoint;

import com.linecorp.armeria.client.Endpoint;

/**
 * {@link Endpoint} selection strategy that creates a {@link EndpointSelector}.
 *  <br/>
 *  策略协调器，并且会创建一个{@link EndpointSelector}
 */
@FunctionalInterface
public interface EndpointSelectionStrategy {

    /**
     * Simple round-robin strategy.
     * <br/>
     * 简单的轮训
     */
    EndpointSelectionStrategy ROUND_ROBIN = new RoundRobinStrategy();

    /**
     * Weighted round-robin strategy.
     * <br/>
     * 权重配比
     */
    EndpointSelectionStrategy WEIGHTED_ROUND_ROBIN = new WeightedRoundRobinStrategy();

    /**
     * Creates a new {@link EndpointSelector} that selects an {@link Endpoint} from the specified
     * {@link EndpointGroup}.
     * <br/>
     * 新建能从指定的{@link EndpointGroup}内挑选{@link Endpoint}的EndpointSelector
     */
    EndpointSelector newSelector(EndpointGroup endpointGroup);
}
