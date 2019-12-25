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

package com.linecorp.armeria.client.grpc;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientFactoryProvider;

/**
 * {@link ClientFactoryProvider} that creates a {@link GrpcClientFactory}.
 * <br/>
 * 创建GrpcClientFactory的提供者。
 *
 * 会在这个类{@link com.linecorp.armeria.client.DefaultClientFactory}的构造方法内，
 * 其通过SPI机制将ClientFactoryProvider的实现类GrpcClientFactoryProvider扫描到。并且调用其newFactory方法。进行ClientFactory的采集。
 */
public final class GrpcClientFactoryProvider implements ClientFactoryProvider {
    @Override
    public ClientFactory newFactory(ClientFactory httpClientFactory) {
        return new GrpcClientFactory(httpClientFactory);
    }
}
