/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client.circuitbreaker;

/**
 * Defines the states of <a href="https://martinfowler.com/bliki/CircuitBreaker.html">circuit breaker</a>.
 */
public enum CircuitState {
    /**
     * Initial state. All requests are sent to the remote service.
     * <br/>
     * 初始化状态，所有的请求都会被发送到远端
     */
    CLOSED,
    /**
     * The circuit is tripped. All requests fail immediately without calling the remote service.
     * <br/>
     * 中断器被打开的状态。所有的请求都会立即失败
     */
    OPEN,
    /**
     * Only one trial request is sent at a time until at least one request succeeds or fails.
     * If it doesn't complete within a certain time, another trial request will be sent again.
     * All other requests fails immediately same as OPEN.
     * <br/>
     * 只有部分请求可以被发送出去，如果成功，则置为Closed； 如果失败，则置为Open
     */
    HALF_OPEN
}
