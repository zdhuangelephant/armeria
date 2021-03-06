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
package com.linecorp.armeria.client.logging;

import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ConnectionPoolListener;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.TextFormatter;
import com.linecorp.armeria.common.util.Ticker;

import io.netty.util.AttributeKey;
import io.netty.util.AttributeMap;

/**
 * Decorates a {@link ConnectionPoolListener} to log the connection pool events.
 * <p>装饰一个{@link ConnectionPoolListener}来记录连接池的事件</p>
 */
public class ConnectionPoolLoggingListener implements ConnectionPoolListener {
    private static final Logger logger = LoggerFactory.getLogger(ConnectionPoolLoggingListener.class);

    /**
     * 声明一个绑定Channel的AttributeKey。
     */
    private static final AttributeKey<Long> OPEN_NANOS =
            AttributeKey.valueOf(ConnectionPoolLoggingListener.class, "OPEN_NANOS");

    // 当前打开链接的计数器
    private final AtomicInteger activeChannels = new AtomicInteger();
    private final Ticker ticker;

    /**
     * Creates a new instance with a {@linkplain Ticker#systemTicker() system ticker}.
     */
    public ConnectionPoolLoggingListener() {
        this(Ticker.systemTicker());
    }

    /**
     * Creates a new instance with an alternative {@link Ticker}.
     *
     * @param ticker an alternative {@link Ticker}
     */
    public ConnectionPoolLoggingListener(Ticker ticker) {
        this.ticker = requireNonNull(ticker, "ticker");
    }

    @Override
    public void connectionOpen(SessionProtocol protocol,
                               InetSocketAddress remoteAddr,
                               InetSocketAddress localAddr,
                               AttributeMap attrs) throws Exception {
        // open一个连接的时候， 会自动增量
        final int activeChannels = this.activeChannels.incrementAndGet();
        if (logger.isInfoEnabled()) {
            // 在连接打开的同时，将OPEN_NANOS的AttributeKey<Long>设置进去。
            attrs.attr(OPEN_NANOS).set(ticker.read());
            logger.info("[L:{} - R:{}][{}] OPEN (active channels: {})",
                        localAddr, remoteAddr, protocol.uriText(), activeChannels);
        }
    }

    @Override
    public void connectionClosed(SessionProtocol protocol,
                                 InetSocketAddress remoteAddr,
                                 InetSocketAddress localAddr,
                                 AttributeMap attrs) throws Exception {
        // 当close一个连接的时候，计数器会减量
        final int activeChannels = this.activeChannels.decrementAndGet();
        if (logger.isInfoEnabled() && attrs.hasAttr(OPEN_NANOS)) {
            final long closeNanos = ticker.read();
            /**
             * 然后在close的时候取出来，进行当前connection的从打开到关闭需要的耗时, 用以作log记录
             */
            final long elapsedNanos = closeNanos - attrs.attr(OPEN_NANOS).get();
            logger.info("[L:{} ! R:{}][{}] CLOSED (lasted for: {}, active channels: {})",
                        localAddr, remoteAddr, protocol.uriText(),
                        TextFormatter.elapsed(elapsedNanos), activeChannels);
        }
    }
}
