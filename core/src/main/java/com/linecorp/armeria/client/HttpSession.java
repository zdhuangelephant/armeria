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

import javax.annotation.Nullable;

import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.internal.InboundTrafficController;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;

interface HttpSession {

    /**
     * 未激活的实例
     */
    HttpSession INACTIVE = new HttpSession() {
        @Nullable
        @Override
        public SessionProtocol protocol() {
            return null;
        }

        @Override
        public boolean canSendRequest() {
            return false;
        }

        @Override
        public InboundTrafficController inboundTrafficController() {
            return InboundTrafficController.disabled();
        }

        @Override
        public int unfinishedResponses() {
            return 0;
        }

        @Override
        public boolean invoke(ClientRequestContext ctx, HttpRequest req, DecodedHttpResponse res) {
            res.close(ClosedSessionException.get());
            return false;
        }

        @Override
        public void retryWithH1C() {
            throw new IllegalStateException();
        }

        @Override
        public void deactivate() {}
    };

    /**
     * 获取最后一个HttpSession
     * @param ch
     * @return
     */
    static HttpSession get(Channel ch) {
        /**
         * 拿到Netty内的pipeline的最后一个HttpSession
         */
        final ChannelHandler lastHandler = ch.pipeline().last();
        if (lastHandler instanceof HttpSession) {
            return (HttpSession) lastHandler;
        }
        return INACTIVE;
    }

    @Nullable
    SessionProtocol protocol();

    boolean canSendRequest();

    /**
     * 获取与当前session绑定的流量控制的Controller
     * @return
     */
    InboundTrafficController inboundTrafficController();

    int unfinishedResponses();

    default boolean hasUnfinishedResponses() {
        return unfinishedResponses() != 0;
    }

    default int maxUnfinishedResponses() {
        return Integer.MAX_VALUE;
    }

    boolean invoke(ClientRequestContext ctx, HttpRequest req, DecodedHttpResponse res);

    /**
     * 尝试用Http1的标准进行重连
     */
    void retryWithH1C();

    /**
     * 灭掉此Channel，让其停止工作
     */
    void deactivate();
}
