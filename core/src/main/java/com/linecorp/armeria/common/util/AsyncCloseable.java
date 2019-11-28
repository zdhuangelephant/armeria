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
package com.linecorp.armeria.common.util;

import java.util.concurrent.CompletableFuture;

/**
 * An object that may hold resources until it is closed. Unlike {@link AutoCloseable}, the {@link #closeAsync()}
 * method releases the resources asynchronously, returning the {@link CompletableFuture} which is completed
 * after the resources are released.
 * <br/>
 * 一个对象在被关闭之前，它会一直持有某个资源。不同于{@link AutoCloseable}，{@link #closeAsync()}会异步的释放资源，在资源被释放后会返回一个代表资源是否完成释放的future，
 */
@FunctionalInterface
public interface AsyncCloseable {
    /**
     * Releases the resources held by this object asynchronously.
     * <br/>
     * 异步释放被this对象持有的资源。
     *
     * @return the {@link CompletableFuture} which is completed after the resources are released 表示该资源是否被释放的future。
     */
    CompletableFuture<?> closeAsync();
}
