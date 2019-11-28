/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.client.endpoint.healthcheck;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientOptionsBuilder;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.SessionProtocol;

/**
 * Provides the properties and operations required for sending health check requests.
 * <br/>
 * 提供了发送探活请求的必要操作和属性
 */
public interface HealthCheckerContext {

    /**
     * Returns the {@link Endpoint} to send health check requests to.
     * <br/>
     * 返回负责发送探活请求的Endpoint
     */
    Endpoint endpoint();

    /**
     * Returns the {@link ClientFactory} which is used for sending health check requests.
     * <br/>
     * 返回负责发送探活请求的ClientFactory
     */
    ClientFactory clientFactory();

    /**
     * Returns the {@link SessionProtocol} to be used when sending health check requests.
     * <br/>
     * 返回负责发送探活请求的SessionProtocol
     */
    SessionProtocol protocol();

    /**
     * Returns the {@link Function} that customizes a {@link Client} that sends health check requests.
     * <br/>
     * 返回负责发送探活请求的构造Client的Function
     */
    Function<? super ClientOptionsBuilder, ClientOptionsBuilder> clientConfigurator();

    /**
     * Returns the {@link ScheduledExecutorService} which is used for scheduling the tasks related with
     * sending health check requests. Note that the {@link ScheduledExecutorService} returned by this method
     * cannot be shut down; calling {@link ExecutorService#shutdown()} or {@link ExecutorService#shutdownNow()}
     * will trigger an {@link UnsupportedOperationException}.
     *
     * <br/>
     * 返回负责调度 与探活请求相关的task 的ScheduledExecutorService。<br/>
     * note： 该方法返回的线程池，是不可以关闭的，如果强行关闭会抛出{@link UnsupportedOperationException}异常。
     */
    ScheduledExecutorService executor();

    /**
     * Returns the delay for the next health check request in milliseconds.
     * <br/>
     * 返回下次发送探活请求的时间间隔(ms)
     */
    long nextDelayMillis();

    /**
     * Updates the health of the {@link Endpoint} being checked.
     * <br/>
     * 更新正在检查的Endpoint的状态
     *
     * @param health {@code 0.0} indicates the {@link Endpoint} is not able to handle any requests.  0.0 表示不能处理任何的请求
     *               A positive value indicates the {@link Endpoint} is able to handle requests.   正数 表示能处理请求
     *               A value greater than {@code 1.0} will be set equal to {@code 1.0}.         // 如果设置>1,则会强制设置为1.0
     *
     */
    void updateHealth(double health);
}
