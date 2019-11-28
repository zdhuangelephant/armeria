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

import java.util.Optional;

interface EventCounter {

    /**
     * Returns the current {@link EventCount}.
     * <br/>
     * 返回当前的{@link EventCount}
     */
    EventCount count();

    /**
     * Counts success events.
     * <br/>
     * 如果被更新，则返回一个成功的{@link EventCount}； 否则返回一个空的{@link EventCount}
     * @return An {@link Optional} containing the current {@link EventCount} if it has been updated,
     *         or else an empty {@link Optional}.
     */
    Optional<EventCount> onSuccess();

    /**
     * Counts failure events.
     * <br/>
     * 如果被更新，则返回一个成功的{@link EventCount}； 否则返回一个空的{@link EventCount}
     *
     * @return An {@link Optional} containing the current {@link EventCount} if it has been updated,
     *         or else an empty {@link Optional}.
     */
    Optional<EventCount> onFailure();
}
