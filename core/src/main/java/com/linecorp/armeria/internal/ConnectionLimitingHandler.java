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

package com.linecorp.armeria.internal;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;

/**
 * Limit the number of open connections to the configured value.
 * {@link ConnectionLimitingHandler} instance would be set to {@link ServerBootstrap#handler(ChannelHandler)}.
 * <br/>
 * 连接数限制戳利器
 */
@Sharable
public final class ConnectionLimitingHandler extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(ConnectionLimitingHandler.class);

    private final Set<Channel> childChannels = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<Channel> unmodifiableChildChannels = Collections.unmodifiableSet(childChannels);
    // 配置的最大连接数 阈值
    private final int maxNumConnections;
    // 连接计数器
    private final AtomicInteger numConnections = new AtomicInteger();

    // logger 记录droppedConnections的日志标记
    private final AtomicBoolean loggingScheduled = new AtomicBoolean();
    // 记录被强制关闭的链接数量
    private final LongAdder numDroppedConnections = new LongAdder();

    public ConnectionLimitingHandler(int maxNumConnections) {
        this.maxNumConnections = validateMaxNumConnections(maxNumConnections);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        final Channel child = (Channel) msg;

        // 连接计数器 + 1
        final int conn = numConnections.incrementAndGet();
        if (conn > 0 && conn <= maxNumConnections) {
            // +1
            childChannels.add(child);
            child.closeFuture().addListener(future -> {
                // 在close的触发的时候， -1;
                childChannels.remove(child);
                numConnections.decrementAndGet();
            });
            super.channelRead(ctx, msg);
        } else {
            // 连接计数器 - 1
            numConnections.decrementAndGet();

            /**
             * SO_LINGER还有一个作用就是用来减少TIME_WAIT套接字的数量。
             * 在设置SO_LINGER选项时，指定等待时间为0，此时调用主动关闭时不会发送FIN来结束连接，而是直接将连接设置为CLOSE状态，清除套接字中的发送和接收缓冲区，直接对对端发送RST包。
             */
            // Set linger option to 0 so that the server doesn't get too many TIME_WAIT states.
            child.config().setOption(ChannelOption.SO_LINGER, 0);
            child.unsafe().closeForcibly();

            // 由于超过了配置的阈值，所以强制关闭的计数器 + 1
            numDroppedConnections.increment();

            // 异步告诉logger需要打印droppedConnections事件
            if (loggingScheduled.compareAndSet(false, true)) {
                ctx.executor().schedule(this::writeNumDroppedConnectionsLog, 1, TimeUnit.SECONDS);
            }
        }
    }
    // 记录被drop掉的链接
    private void writeNumDroppedConnectionsLog() {
        loggingScheduled.set(false);

        final long dropped = numDroppedConnections.sumThenReset();
        if (dropped > 0) {
            logger.warn("Dropped {} connection(s) to limit the number of open connections to {}",
                        dropped, maxNumConnections);
        }
    }

    /**
     * Returns the maximum allowed number of open connections.
     */
    public int maxNumConnections() {
        return maxNumConnections;
    }

    /**
     * Returns the number of open connections.
     */
    public int numConnections() {
        return numConnections.get();
    }

    /**
     * Returns the immutable set of child {@link Channel}s.
     */
    public Set<Channel> children() {
        return unmodifiableChildChannels;
    }

    /**
     * Validates the maximum allowed number of open connections. It must be a positive number.
     */
    public static int validateMaxNumConnections(int maxNumConnections) {
        if (maxNumConnections <= 0) {
            throw new IllegalArgumentException("maxNumConnections: " + maxNumConnections + " (expected: > 0)");
        }
        return maxNumConnections;
    }
}
