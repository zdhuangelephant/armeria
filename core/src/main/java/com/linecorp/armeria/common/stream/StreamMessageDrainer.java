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

package com.linecorp.armeria.common.stream;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;

import io.netty.util.ReferenceCountUtil;

/**
 * 一旦传递Subscriber的实例给{@link Publisher#subscribe(Subscriber)}后(即调用后)， 这边会立马收到需要调用{@link Subscriber#onSubscribe(Subscription)}的通知。
 * 除此之外，将不会收到任何通知，一直到{@link Subscription#request(long)}被调用。
 * <br/>
 *
 * Will receive call to {@link #onSubscribe(Subscription)} once after passing an instance of {@link Subscriber} to {@link Publisher#subscribe(Subscriber)}.
 * <p>
 * No further notifications will be received until {@link Subscription#request(long)} is called.
 * <p>
 * After signaling demand: 发出信号以后的要求
 * <ul>
 * <li>{@link #onNext(Object)}方法的一次或者多次的调用，直到通过{@link Subscription#request(long)}约定的上限数量。</li>
 * <li>单一的{@link #onError(Throwable)}调用，或者{@link Subscriber#onComplete()} 已经发送被发送完毕
 * </ul>
 * <p>
 * 不管订阅者能处理多大的数据量，Demand只能经过{@link Subscription#request(long)}来设置。
 *
 */
final class StreamMessageDrainer<T> implements Subscriber<T> {

    private final CompletableFuture<List<T>> future = new CompletableFuture<>();

    @Nullable
    private Builder<T> drained = ImmutableList.builder();

    private final boolean withPooledObjects;

    StreamMessageDrainer(boolean withPooledObjects) {
        this.withPooledObjects = withPooledObjects;
    }

    CompletableFuture<List<T>> future() {
        return future;
    }

    @Override
    public void onSubscribe(Subscription s) {
        s.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(T t) {
        assert drained != null;
        drained.add(t);
    }

    @Override
    public void onError(Throwable t) {
        if (withPooledObjects) {
            assert drained != null;
            drained.build().forEach(ReferenceCountUtil::safeRelease);
        }
        // Dereference to make the objects GC'd.
        drained = null;

        future.completeExceptionally(t);
    }

    @Override
    public void onComplete() {
        assert drained != null;
        future.complete(drained.build());
        // Dereference to make the objects GC'd.
        drained = null;
    }
}
