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

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.armeria.client.endpoint.healthcheck.HealthCheckedEndpointGroup.DEFAULT_HEALTH_CHECK_RETRY_BACKOFF;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.function.Function;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientOptionsBuilder;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.AsyncCloseable;

/**
 * A skeletal builder implementation for creating a new {@link HealthCheckedEndpointGroup}.
 * <br/>
 * Builder的构建为了创建新的{@link HealthCheckedEndpointGroup}
 */
public abstract class AbstractHealthCheckedEndpointGroupBuilder {
    // 一个EndpointGroup代理
    private final EndpointGroup delegate;
    // 协议支持HTTP
    private SessionProtocol protocol = SessionProtocol.HTTP;
    // 默认的Backoff，固定间隔
    private Backoff retryBackoff = DEFAULT_HEALTH_CHECK_RETRY_BACKOFF;
    // DefaultClientFactory 默认工厂
    private ClientFactory clientFactory = ClientFactory.DEFAULT;
    private Function<? super ClientOptionsBuilder, ClientOptionsBuilder> configurator = Function.identity();
    private int port;

    /**
     * Creates a new {@link AbstractHealthCheckedEndpointGroupBuilder}.
     *
     * @param delegate the {@link EndpointGroup} which provides the candidate {@link Endpoint}s
     */
    protected AbstractHealthCheckedEndpointGroupBuilder(EndpointGroup delegate) {
        this.delegate = requireNonNull(delegate, "delegate");
    }

    /**
     * Sets the {@link ClientFactory} to use when making health check requests. This should generally be the
     * same as the {@link ClientFactory} used when creating a {@link Client} stub using the
     * {@link EndpointGroup}.
     */
    public AbstractHealthCheckedEndpointGroupBuilder clientFactory(ClientFactory clientFactory) {
        this.clientFactory = requireNonNull(clientFactory, "clientFactory");
        return this;
    }

    /**
     * Sets the {@link SessionProtocol} to be used when making health check requests.
     */
    public AbstractHealthCheckedEndpointGroupBuilder protocol(SessionProtocol protocol) {
        this.protocol = requireNonNull(protocol, "protocol");
        return this;
    }

    /**
     * Sets the port where a health check request will be sent instead of the original port number
     * specified by {@link EndpointGroup}'s {@link Endpoint}s. This property is useful when your
     * server listens to health check requests on a different port.
     *
     * @deprecated Use {@link #port(int)}.
     */
    @Deprecated
    public AbstractHealthCheckedEndpointGroupBuilder healthCheckPort(int port) {
        return port(port);
    }

    /**
     * Sets the port where a health check request will be sent instead of the original port number
     * specified by {@link EndpointGroup}'s {@link Endpoint}s. This property is useful when your
     * server listens to health check requests on a different port.
     */
    public AbstractHealthCheckedEndpointGroupBuilder port(int port) {
        checkArgument(port > 0 && port <= 65535,
                      "port: %s (expected: 1-65535)", port);
        this.port = port;
        return this;
    }

    /**
     * Sets the interval between health check requests. Must be positive.
     */
    public AbstractHealthCheckedEndpointGroupBuilder retryInterval(Duration retryInterval) {
        requireNonNull(retryInterval, "retryInterval");
        checkArgument(!retryInterval.isNegative() && !retryInterval.isZero(),
                      "retryInterval: %s (expected > 0)", retryInterval);
        return retryIntervalMillis(retryInterval.toMillis());
    }

    /**
     * Sets the interval between health check requests in milliseconds. Must be positive.
     */
    public AbstractHealthCheckedEndpointGroupBuilder retryIntervalMillis(long retryIntervalMillis) {
        checkArgument(retryIntervalMillis > 0,
                      "retryIntervalMillis: %s (expected > 0)", retryIntervalMillis);
        return retryBackoff(Backoff.fixed(retryIntervalMillis).withJitter(0.2));
    }

    /**
     * Sets the backoff between health check requests.
     */
    public AbstractHealthCheckedEndpointGroupBuilder retryBackoff(Backoff retryBackoff) {
        this.retryBackoff = requireNonNull(retryBackoff, "retryBackoff");
        return this;
    }

    /**
     * Sets the {@link Function} that customizes a {@link Client} that sends health check requests.
     * <pre>{@code
     * builder.withClientOptions(b -> {
     *     return b.setHttpHeader(HttpHeaders.AUTHORIZATION,
     *                            "bearer my-access-token")
     *             .responseTimeout(Duration.ofSeconds(3));
     * });
     * }</pre>
     */
    public AbstractHealthCheckedEndpointGroupBuilder withClientOptions(
            Function<? super ClientOptionsBuilder, ClientOptionsBuilder> configurator) {
        this.configurator = this.configurator.andThen(requireNonNull(configurator, "configurator"));
        return this;
    }

    /**
     * Returns a newly created {@link HealthCheckedEndpointGroup} based on the properties set so far.
     * <br/>
     * 返回到目前为止已经设置完毕属性的最新的HealthCheckedEndpointGroup
     */
    public HealthCheckedEndpointGroup build() {
        return new HealthCheckedEndpointGroup(delegate, clientFactory, protocol, port,
                                              retryBackoff, configurator, newCheckerFactory());
    }

    /**
     * Returns the {@link Function} that starts to send health check requests to the {@link Endpoint}
     * specified in a given {@link HealthCheckerContext} when invoked. The {@link Function} must update
     * the health of the {@link Endpoint} with a value between [0, 1] via
     * {@link HealthCheckerContext#updateHealth(double)}. {@link HealthCheckedEndpointGroup} will call
     * {@link AsyncCloseable#closeAsync()} on the {@link AsyncCloseable} returned by the {@link Function}
     * when it needs to stop sending health check requests.
     *
     * <br/>
     * 1、返回开始发送探活请求到由HealthCheckerContext指定Endpoint上的Function
     * 2、此Function通过{@link HealthCheckerContext#updateHealth(double)}方法，并用[0, 1]范围内的值必须更新Endpoint得健康状态
     * 3、当需要停止发送探活请求的时候，HealthCheckedEndpointGroup将会调用此Function返回的{@link AsyncCloseable}对象的{@link AsyncCloseable#closeAsync()}
     */
    protected abstract Function<? super HealthCheckerContext, ? extends AsyncCloseable> newCheckerFactory();
}
