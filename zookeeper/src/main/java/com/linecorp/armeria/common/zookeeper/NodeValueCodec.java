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
package com.linecorp.armeria.common.zookeeper;

import static java.util.Objects.requireNonNull;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import javax.annotation.Nullable;

import com.linecorp.armeria.client.Endpoint;

/**
 * Decode and encode between list of zNode value strings and list of {@link Endpoint}s.
 * <br/>
 * 元素是String类型的zNode集合和Endpoint的集合 编解码的类
 */
public interface NodeValueCodec {

    /**
     * Default {@link NodeValueCodec} implementation which assumes zNode value is a comma-separated
     * string. Each element of the zNode value represents an endpoint whose format is
     * {@code <host>[:<port_number>[:weight]]}, such as:
     * <ul>
     *   <li>{@code "foo.com"} - default port number, default weight (1000)</li>
     *   <li>{@code "bar.com:8080} - port number 8080, default weight (1000)</li>
     *   <li>{@code "10.0.2.15:0:500} - default port number, weight 500</li>
     *   <li>{@code "192.168.1.2:8443:700} - port number 8443, weight 700</li>
     * </ul>
     * the segment and field delimiter can be specified, default will be "," and ":"
     * Note that the port number must be specified when you want to specify the weight.
     * <br/>
     * 默认的{@code <host>[:<port_number>[:weight]]}格式。如上面所示！
     * 注意一点的是，如果要指定权重的话，那同时端口必须是指定的。
     */
    NodeValueCodec DEFAULT = DefaultNodeValueCodec.INSTANCE;

    /**
     * Decodes a zNode value into a set of {@link Endpoint}s.
     * <br/>
     * 解码某个zNode的值放入{@link Endpoint}的集合内
     *
     * @param zNodeValue zNode value
     * @return the list of {@link Endpoint}s
     */
    default Set<Endpoint> decodeAll(byte[] zNodeValue) {
        requireNonNull(zNodeValue, "zNodeValue");
        return decodeAll(new String(zNodeValue, StandardCharsets.UTF_8));
    }

    /**
     * Decodes a zNode value into a set of {@link Endpoint}s.
     * <br/>
     * 解码某个zNode的值放入{@link Endpoint}的集合内
     * @param zNodeValue zNode value
     * @return the list of {@link Endpoint}s
     */
    Set<Endpoint> decodeAll(String zNodeValue);

    /**
     * Decodes a zNode value to a {@link Endpoint}.
     * <br/>
     * 解码某个zNode的值放入{@link Endpoint}
     *
     * @param zNodeValue ZooKeeper node value
     * @return an {@link Endpoint} or {@code null} if value needs to be skipped
     */
    @Nullable
    default Endpoint decode(byte[] zNodeValue) {
        requireNonNull(zNodeValue, "zNodeValue");
        return decode(new String(zNodeValue, StandardCharsets.UTF_8));
    }

    /**
     * Decodes a zNode value to a {@link Endpoint}.
     *
     * <br/>
     * 解码某个zNode的值放入{@link Endpoint}
     *
     * @param zNodeValue ZooKeeper node value
     * @return an {@link Endpoint} or {@code null} if value needs to be skipped
     */
    @Nullable
    Endpoint decode(String zNodeValue);

    /**
     * Encodes a set of {@link Endpoint}s into a byte array representation.
     * <br/>
     * 编码{@link Endpoint}的集合
     *
     * @param endpoints set of {@link Endpoint}s
     * @return a byte array
     */
    byte[] encodeAll(Iterable<Endpoint> endpoints);

    /**
     * Encodes a single {@link Endpoint} into a byte array representation.
     *
     * <br/>
     * 编码指定的{@link Endpoint}
     *
     * @param endpoint  an {@link Endpoint}
     * @return a byte array
     */
    byte[] encode(Endpoint endpoint);
}
