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
package com.linecorp.armeria.internal.zookeeper;

import org.apache.curator.retry.ExponentialBackoffRetry;

/**
 * ZooKeeper related constant values.
 * zk相关的常量Cons
 */
public final class ZooKeeperDefaults {
    /**
     * 默认的连接超时时间
     */
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 1000;
    /**
     * 默认的会话超时时间
     */
    public static final int DEFAULT_SESSION_TIMEOUT_MS = 10000;

    /**
     * 默认的重试策略
     */
    public static final ExponentialBackoffRetry DEFAULT_RETRY_POLICY =
            new ExponentialBackoffRetry(DEFAULT_CONNECT_TIMEOUT_MS, 3);

    private ZooKeeperDefaults() {}
}
