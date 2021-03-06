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

package com.linecorp.armeria.client;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Streams;

import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.util.ReleasableHolder;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;

/**
 * A {@link ClientFactory} which combines all discovered {@link ClientFactory} implementations.
 *
 * <h3>How are the {@link ClientFactory}s discovered?</h3>
 *
 * <p>{@link DefaultClientFactory} looks up the {@link ClientFactoryProvider}s available in the current JVM
 * using Java SPI (Service Provider Interface). The {@link ClientFactoryProvider} implementations will create
 * the {@link ClientFactory} implementations.
 *
 * <br/>
 * 这个类很了不起！
 * <br/>
 * DefaultClientFactory会循环ClientFactoryProvider的所有实现子类，利用的是java的SPI机制，
 * 并且会自动调用{@link ClientFactoryProvider#newFactory(ClientFactory)}从而获取诸如像thrift/grpc/http等所有工厂类
 *
 */
final class DefaultClientFactory extends AbstractClientFactory {

    private static final Logger logger = LoggerFactory.getLogger(DefaultClientFactory.class);

    private static volatile boolean shutdownHookDisabled;

    static {
        // 如果当前的类加载器是系统类加载器，当jvm退出时，会自动释放掉所有的工厂资源
        if (DefaultClientFactory.class.getClassLoader() == ClassLoader.getSystemClassLoader()) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (!shutdownHookDisabled) {
                    ClientFactory.closeDefault();
                }
            }));
        }
    }

    static void disableShutdownHook0() {
        shutdownHookDisabled = true;
    }

    /**
     * 这个httpClientFactory实际就是传递到父级DecoratingClientFactory内代理clientFactory的delegate
     *
     * <br/>
     * httpClientFactory是Http客户端的工厂， 这个类的影响作用面很广泛呀。
     */
    private final HttpClientFactory httpClientFactory;
    /**
     * 存储着所有的Schema和ClientFactory的对应关系
     */
    private final Map<Scheme, ClientFactory> clientFactories;

    /**
     * 这个成员属性实际上就是availableClientFactories
     */
    private final List<ClientFactory> clientFactoriesToClose;

    DefaultClientFactory(HttpClientFactory httpClientFactory) {
        this.httpClientFactory = httpClientFactory;

        final List<ClientFactory> availableClientFactories = new ArrayList<>();
        availableClientFactories.add(httpClientFactory);

        /**
         * 这个地方是非常厉害的，该类的核心功能的实现，会扫描所有ClientFactoryProvider的实现类，并且将其全部加入到availableClientFactories
         */
        Streams.stream(ServiceLoader.load(ClientFactoryProvider.class,
                                          DefaultClientFactory.class.getClassLoader()))
               .map(provider -> provider.newFactory(httpClientFactory))
               .forEach(availableClientFactories::add);

        final ImmutableMap.Builder<Scheme, ClientFactory> builder = ImmutableMap.builder();
        for (ClientFactory f : availableClientFactories) {
            f.supportedSchemes().forEach(s -> builder.put(s, f));
        }

        clientFactories = builder.build();
        clientFactoriesToClose = ImmutableList.copyOf(availableClientFactories).reverse();
    }

    @Override
    public Set<Scheme> supportedSchemes() {
        return clientFactories.keySet();
    }

    @Override
    public EventLoopGroup eventLoopGroup() {
        return httpClientFactory.eventLoopGroup();
    }

    @Override
    public Supplier<EventLoop> eventLoopSupplier() {
        return httpClientFactory.eventLoopSupplier();
    }

    @Override
    public ReleasableHolder<EventLoop> acquireEventLoop(Endpoint endpoint) {
        return httpClientFactory.acquireEventLoop(endpoint);
    }

    @Override
    public MeterRegistry meterRegistry() {
        return httpClientFactory.meterRegistry();
    }

    @Override
    public void setMeterRegistry(MeterRegistry meterRegistry) {
        httpClientFactory.setMeterRegistry(meterRegistry);
    }

    @Override
    public <T> T newClient(URI uri, Class<T> clientType, ClientOptions options) {
        final Scheme scheme = validateScheme(uri);
        return clientFactories.get(scheme).newClient(uri, clientType, options);
    }

    @Override
    public <T> T newClient(Scheme scheme, Endpoint endpoint, @Nullable String path, Class<T> clientType,
                           ClientOptions options) {
        final Scheme validatedScheme = validateScheme(scheme);
        return clientFactories.get(validatedScheme)
                              .newClient(validatedScheme, endpoint, path, clientType, options);
    }

    @Override
    public <T> Optional<ClientBuilderParams> clientBuilderParams(T client) {
        for (ClientFactory factory : clientFactories.values()) {
            final Optional<ClientBuilderParams> params = factory.clientBuilderParams(client);
            if (params.isPresent()) {
                return params;
            }
        }
        return Optional.empty();
    }

    @Override
    public void close() {
        // The global default should never be closed.
        // 如果是默认的ClientFactory， 则不应该被关闭
        if (this == ClientFactory.DEFAULT) {
            logger.debug("Refusing to close the default {}; must be closed via closeDefault()",
                         ClientFactory.class.getSimpleName());
            return;
        }

        // 除了默认的ClientFactory以外， 在客户端显示的调用了close方法下，其余的都可以被关闭

        doClose();
    }

    void doClose() {
        clientFactoriesToClose.forEach(ClientFactory::close);
    }
}
