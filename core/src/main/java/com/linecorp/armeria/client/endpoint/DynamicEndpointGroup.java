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
package com.linecorp.armeria.client.endpoint;

import static com.google.common.collect.ImmutableList.toImmutableList;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.util.AbstractListenable;

/**
 * A dynamic {@link EndpointGroup}. The list of {@link Endpoint}s can be updated dynamically.
 * <br/>
 * <ol>
 *     <li>就是管理一个集合，这个集合内存放着隶属于这个EndpointGroup的所有Endpoint实例。</li>
 *     <li>endpoints内的所有{@link Endpoint}都可以被动态的更新，并且每一次的add/remove都会触发notifyListeners。</li>
 *     <li>并且setEndpoint()和addEndpoint()两者调用的结束都会把initialFuture标记为完成状态。</li>
 * </ol>
 */
public class DynamicEndpointGroup extends AbstractListenable<List<Endpoint>> implements EndpointGroup {
    // 盛放所有Endpoint的容器
    private volatile List<Endpoint> endpoints = ImmutableList.of();
    // 操作容器的控制锁
    private final Lock endpointsLock = new ReentrantLock();

    // initial的future
    private final CompletableFuture<List<Endpoint>> initialEndpointsFuture = new CompletableFuture<>();

    @Override
    public final List<Endpoint> endpoints() {
        return endpoints;
    }

    /**
     * Returns the {@link CompletableFuture} which is completed when the initial {@link Endpoint}s are ready.
     */
    @Override
    public CompletableFuture<List<Endpoint>> initialEndpointsFuture() {
        return initialEndpointsFuture;
    }

    /**
     * Adds the specified {@link Endpoint} to current {@link Endpoint} list.
     * <br/>
     * 添加指定的Endpoint到当前的Endpoint集合内
     */
    protected final void addEndpoint(Endpoint e) {
        final List<Endpoint> newEndpoints;
        // 对于集合的访问，一定是线程安全的
        endpointsLock.lock();
        try {
            final List<Endpoint> newEndpointsUnsorted = Lists.newArrayList(endpoints);
            newEndpointsUnsorted.add(e);
            endpoints = newEndpoints = ImmutableList.sortedCopyOf(newEndpointsUnsorted);
        } finally {
            endpointsLock.unlock();
        }
        // 对于每一个添加进来的Endpoint，都需要通知监听者，这个endpoints的集合被监控起来了，哈哈
        notifyListeners(newEndpoints);
        // initialFuture标记为完成状态
        completeInitialEndpointsFuture(newEndpoints);
    }

    /**
     * Removes the specified {@link Endpoint} from current {@link Endpoint} list.
     * <br/>
     * 类比于上面的addEndpoint
     */
    protected final void removeEndpoint(Endpoint e) {
        final List<Endpoint> newEndpoints;
        endpointsLock.lock();
        try {
            // 删除指定的e，并且重新给endpoints赋值
            endpoints = newEndpoints = endpoints.stream()
                                                .filter(endpoint -> !endpoint.equals(e))
                                                .collect(toImmutableList());
        } finally {
            endpointsLock.unlock();
        }
        // 每次的删除操作，即变更，都需要通知给所有的监听者
        notifyListeners(newEndpoints);
    }

    /**
     * Sets the specified {@link Endpoint}s as current {@link Endpoint} list.
     * <br/>
     * 设置指定的endpoints为当前类的endpoints成员变量
     */
    protected final void setEndpoints(Iterable<Endpoint> endpoints) {
        final List<Endpoint> oldEndpoints = this.endpoints;
        final List<Endpoint> newEndpoints = ImmutableList.sortedCopyOf(endpoints);

        if (oldEndpoints.equals(newEndpoints)) {
            return;
        }

        // 上锁
        endpointsLock.lock();
        try {
            // 进行替换操作
            this.endpoints = newEndpoints;
        } finally {
            // 解锁
            endpointsLock.unlock();
        }
        // 每次的this.endpoints的变更，都需要通知监听者
        notifyListeners(newEndpoints);
        // initialFuture标记为完成状态
        completeInitialEndpointsFuture(newEndpoints);
    }

    // 标记future的状态为完成。
    private void completeInitialEndpointsFuture(List<Endpoint> endpoints) {
        // 必须endpoints有元素才可以将initialEndpointsFuture置为当前endpoints的引用
        if (!endpoints.isEmpty() && !initialEndpointsFuture.isDone()) {
            initialEndpointsFuture.complete(endpoints);
        }
    }

    @Override
    public void close() {
        if (!initialEndpointsFuture.isDone()) {
            // 就算是当前的task是正在运行着的， 也要强制将线程中断掉。
            initialEndpointsFuture.cancel(true);
        }
    }
}
