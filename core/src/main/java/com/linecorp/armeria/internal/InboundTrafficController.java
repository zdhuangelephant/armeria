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

package com.linecorp.armeria.internal;

import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;
import com.google.common.math.IntMath;

import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;

/**
 * 流控处理器， 对于进来的流量
 */
public final class InboundTrafficController extends AtomicInteger {

    private static final long serialVersionUID = 420503276551000218L;

    /**
     * 不可用的流控处理器
     */
    private static final InboundTrafficController DISABLED = new InboundTrafficController(null, 0, 0);
    /**
     * 读取的次数计数器
     */
    private static int numDeferredReads;

    public static int numDeferredReads() {
        return numDeferredReads;
    }

    /**
     * http 1.0 流控实例
     * @param channel
     * @return
     */
    public static InboundTrafficController ofHttp1(Channel channel) {
        return new InboundTrafficController(channel, 128 * 1024, 64 * 1024);
    }

    /**
     * http 2.0 流控实例
     * @param channel
     * @param connectionWindowSize
     * @return
     */
    public static InboundTrafficController ofHttp2(Channel channel, int connectionWindowSize) {
        // Compensate for protocol overhead traffic incurred by frame headers, etc.
        // This is a very rough estimate, but it should not hurt.
        connectionWindowSize = IntMath.saturatedAdd(connectionWindowSize, 1024);

        final int highWatermark = Math.max(connectionWindowSize, 128 * 1024);
        final int lowWatermark = highWatermark >>> 1;
        return new InboundTrafficController(channel, highWatermark, lowWatermark);
    }

    /**
     * 禁用的流控实例
     * @return
     */
    public static InboundTrafficController disabled() {
        return DISABLED;
    }

    @Nullable
    private final ChannelConfig cfg;
    private final int highWatermark;
    private final int lowWatermark;
    private volatile boolean suspended;

    private InboundTrafficController(@Nullable Channel channel, int highWatermark, int lowWatermark) {
        cfg = channel != null ? channel.config() : null;
        this.highWatermark = highWatermark;
        this.lowWatermark = lowWatermark;
    }

    /**
     * @param numProducedBytes 即将倒入槽内的字节数
     */
    public void inc(int numProducedBytes) {
        final int oldValue = getAndAdd(numProducedBytes);
        /**
         * 当槽内的字节水位超过阈值后，就会触发io读取当前槽内的数据
         */
        if (oldValue <= highWatermark && oldValue + numProducedBytes > highWatermark) {
            // Just went above high watermark
            // 超过最高水位的阈值
            if (cfg != null) {
                // 告诉netty不要去向该channel内读入数据了，已经满负载了
                cfg.setAutoRead(false);
                // 将读取次数累加
                numDeferredReads++;
                // 将本channel置为挂起，即不可接客
                suspended = true;
            }
        }
    }

    /**
     *
     * @param numConsumedBytes  即将从槽内被取出的字节数
     */
    public void dec(int numConsumedBytes) {
        final int oldValue = getAndAdd(-numConsumedBytes);
        if (oldValue > lowWatermark && oldValue - numConsumedBytes <= lowWatermark) {
            // Just went below low watermark
            if (cfg != null) {
                // 告诉netty可以继续向channel内读入数据了
                cfg.setAutoRead(true);
                // 将本channel置为可以正常接客
                suspended = false;
            }
        }
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("suspended", suspended)
                          .add("unconsumed", get())
                          .add("watermarks", highWatermark + "/" + lowWatermark)
                          .toString();
    }
}
