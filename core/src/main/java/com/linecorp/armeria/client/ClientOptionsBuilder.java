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
package com.linecorp.armeria.client;

/**
 * Creates a new {@link ClientOptions} using the builder pattern.
 * <p>创建{@link ClientOptions}实例，通过Builder模式</p>
 *
 * @see ClientBuilder
 */
public final class ClientOptionsBuilder extends AbstractClientOptionsBuilder<ClientOptionsBuilder> {

    /**
     * Creates a new instance with the default options.
     * <p>通过默认options创建一个新的实例</p>
     */
    public ClientOptionsBuilder() {}

    /**
     * Creates a new instance with the specified base options.
     * 通过指定的options创建一个实例
     */
    public ClientOptionsBuilder(ClientOptions options) {
        super(options);
    }

    /**
     * Returns a newly-created {@link ClientOptions} based on the {@link ClientOptionValue}s of this builder.
     * <p>创建一个新的ClientOptions实例，基于这个builder目前所有的ClientOptionValue </p>
     */
    public ClientOptions build() {
        return buildOptions();
    }
}
