/*
 * Copyright 2018 LINE Corporation
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

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.ReleasableHolder;

import io.netty.channel.Channel;

/**
 * 可被池化的Channel
 */
abstract class PooledChannel implements ReleasableHolder<Channel> {
    //  PooledChannel与之绑定的channel
    private final Channel channel;
    private final SessionProtocol protocol;

    PooledChannel(Channel channel, SessionProtocol protocol) {
        this.channel = requireNonNull(channel, "channel");
        this.protocol = requireNonNull(protocol, "protocol");
    }

    @Override
    public Channel get() {
        return channel;
    }

    // 注意: 当前类没有实现release()。

    SessionProtocol protocol() {
        return protocol;
    }
}
