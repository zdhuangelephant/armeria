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

import java.net.URI;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.util.ReleasableHolder;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;

/**
 * Creates and manages clients.
 *
 * <br/>
 * 创建并管理客户端
 *
 * <h3>Life cycle of the default {@link ClientFactory}</h3>
 * <p>
 * {@link Clients} or {@link ClientBuilder} uses {@link #DEFAULT}, the default {@link ClientFactory},
 * unless you specified a {@link ClientFactory} explicitly. Calling {@link #close()} on the default
 * {@link ClientFactory} will neither terminate its I/O threads nor release other related resources unlike
 * other {@link ClientFactory} to protect itself from accidental premature termination.
 * </p>
 * <br/>
 * Clients ClientBuilder 两者皆是使用DEFAULT，除非你显示的使用其他的工厂。注意一点的是，在DEFAULT#close()既不中断IO操作，也不释放相关资源。
 * <p>
 * Instead, when the current {@link ClassLoader} is {@linkplain ClassLoader#getSystemClassLoader() the system
 * class loader}, a {@linkplain Runtime#addShutdownHook(Thread) shutdown hook} is registered so that they are
 * released when the JVM exits.
 *
 * </p><p>
 * If you are in a multi-classloader environment or you desire an early/explicit termination of the default
 * {@link ClientFactory}, use {@link #closeDefault()}.
 * <br/>
 * 如果你在一个多classloader的环境内或者你渴望一个中断，那么你可以通过调用closeDefault();
 * </p>
 */
public interface ClientFactory extends AutoCloseable {

    /**
     * The default {@link ClientFactory} implementation.
     * {@link DefaultClientFactory} 通过Java的SPI机制将ClientFactoryProvider的所有实现子类收入囊中
     * <br/>
     * 默认的ClientFactory的实现
     */
    ClientFactory DEFAULT = new ClientFactoryBuilder().build();

    /**
     * Closes the default {@link ClientFactory}.
     * <br/>
     * DEFAULT 工厂的真正的关闭方法，即释放资源
     */
    static void closeDefault() {
        LoggerFactory.getLogger(ClientFactory.class).debug(
                "Closing the default {}", ClientFactory.class.getSimpleName());
        ((DefaultClientFactory) DEFAULT).doClose();
    }

    /**
     * Disables the {@linkplain Runtime#addShutdownHook(Thread) shutdown hook} which closes
     * {@linkplain #DEFAULT the default <code>ClientFactory</code>}. This method is useful when you need
     * full control over the life cycle of the default {@link ClientFactory}.
     * <br/>
     *
     */
    static void disableShutdownHook() {
        DefaultClientFactory.disableShutdownHook0();
    }

    /**
     * Returns the {@link Scheme}s supported by this {@link ClientFactory}.
     * <br/>
     * 返回这个ClientFactory的支持的Scheme
     */
    Set<Scheme> supportedSchemes();

    /**
     * Returns the {@link EventLoopGroup} being used by this {@link ClientFactory}. Can be used to, e.g.,
     * schedule a periodic task without creating a separate event loop. Use {@link #eventLoopSupplier()}
     * instead if what you need is an {@link EventLoop} rather than an {@link EventLoopGroup}.
     * <br/>
     * 返回该ClientFactory持有的EventLoopGroup，也可以用来调度周期任务。
     */
    EventLoopGroup eventLoopGroup();

    /**
     * Returns a {@link Supplier} that provides one of the {@link EventLoop}s being used by this
     * {@link ClientFactory}.
     * <br/>
     * 返回该ClientFactory所持有的EventLoop
     */
    Supplier<EventLoop> eventLoopSupplier();

    /**
     * Acquires an {@link EventLoop} that is expected to handle a connection to the specified {@link Endpoint}.
     * The caller must release the returned {@link EventLoop} back by calling {@link ReleasableHolder#release()}
     * so that {@link ClientFactory} utilizes {@link EventLoop}s efficiently.
     * <br/>
     * 获取一个处理该endpoint的EventLoop，调用者使用完毕以后，用户必需显示的调用{@link ReleasableHolder#release()}来释放该链接。
     */
    ReleasableHolder<EventLoop> acquireEventLoop(Endpoint endpoint);

    /**
     * Returns the {@link MeterRegistry} that collects various stats.
     * <br/>
     * 返回一个{@link MeterRegistry}来收集各种指标数据
     */
    MeterRegistry meterRegistry();

    /**
     * Sets the {@link MeterRegistry} that collects various stats. Note that this method is intended to be
     * used during the initialization phase of an application, so that the application gets a chance to
     * switch to the preferred {@link MeterRegistry} implementation. Invoking this method after this factory
     * started to export stats to the old {@link MeterRegistry} may result in undocumented behavior.
     *
     * <br/>
     * 更倾向于项目启动的时候进行注册
     */
    void setMeterRegistry(MeterRegistry meterRegistry);

    /**
     * Creates a new client that connects to the specified {@code uri}.
     *
     * @param uri the URI of the server endpoint
     * @param clientType the type of the new client
     * @param options the {@link ClientOptionValue}s
     */
    <T> T newClient(String uri, Class<T> clientType, ClientOptionValue<?>... options);

    /**
     * Creates a new client that connects to the specified {@code uri}.
     *
     * @param uri the URI of the server endpoint
     * @param clientType the type of the new client
     * @param options the {@link ClientOptions}
     */
    <T> T newClient(String uri, Class<T> clientType, ClientOptions options);

    /**
     * Creates a new client that connects to the specified {@link URI}.
     *
     * @param uri the URI of the server endpoint
     * @param clientType the type of the new client
     * @param options the {@link ClientOptionValue}s
     */
    <T> T newClient(URI uri, Class<T> clientType, ClientOptionValue<?>... options);

    /**
     * Creates a new client that connects to the specified {@link URI}.
     *
     * @param uri the URI of the server endpoint
     * @param clientType the type of the new client
     * @param options the {@link ClientOptions}
     */
    <T> T newClient(URI uri, Class<T> clientType, ClientOptions options);

    /**
     * Creates a new client that connects to the specified {@link Endpoint} with the {@link Scheme}.
     *
     * @param scheme the {@link Scheme} for the {@code endpoint}
     * @param endpoint the server {@link Endpoint}
     * @param clientType the type of the new client
     * @param options the {@link ClientOptionValue}s
     */
    <T> T newClient(Scheme scheme, Endpoint endpoint, Class<T> clientType, ClientOptionValue<?>... options);

    /**
     * Creates a new client that connects to the specified {@link Endpoint} with the {@link Scheme}.
     *
     * @param scheme the {@link Scheme} for the {@code endpoint}
     * @param endpoint the server {@link Endpoint}
     * @param clientType the type of the new client
     * @param options the {@link ClientOptions}
     */
    <T> T newClient(Scheme scheme, Endpoint endpoint, Class<T> clientType, ClientOptions options);

    /**
     * Creates a new client that connects to the specified {@link Endpoint} with the {@link Scheme}
     * and {@code path}.
     *
     * @param scheme the {@link Scheme} for the {@code endpoint}
     * @param endpoint the server {@link Endpoint}
     * @param path the service {@code path}
     * @param clientType the type of the new client
     * @param options the {@link ClientOptionValue}s
     */
    <T> T newClient(Scheme scheme, Endpoint endpoint, @Nullable String path, Class<T> clientType,
                    ClientOptionValue<?>... options);

    /**
     * Creates a new client that connects to the specified {@link Endpoint} with the {@link Scheme}
     * and {@code path}.
     *
     * @param scheme the {@link Scheme} for the {@code endpoint}
     * @param endpoint the server {@link Endpoint}
     * @param path the service {@code path}
     * @param clientType the type of the new client
     * @param options the {@link ClientOptions}
     */
    <T> T newClient(Scheme scheme, Endpoint endpoint, @Nullable String path, Class<T> clientType,
                    ClientOptions options);

    /**
     * Returns the {@link ClientBuilderParams} held in {@code client}. This is used when creating a new derived
     * {@link Client} which inherits {@link ClientBuilderParams} from {@code client}. If this
     * {@link ClientFactory} does not know how to handle the {@link ClientBuilderParams} for the provided
     * {@code client}, it should return {@link Optional#empty()}.
     * <br/>
     * 返回Client端持有的ClientBuilderParams。如果ClientFactory不知道如何处理ClientBuilderParams，那么该方法将会返回{@link Optional#empty()}.
     */
    <T> Optional<ClientBuilderParams> clientBuilderParams(T client);

    /**
     * Closes all clients managed by this factory and shuts down the {@link EventLoopGroup}
     * created implicitly by this factory.
     * <br/>
     * 关闭所有被该ClientFactory所管理的连接
     */
    @Override
    void close();
}
