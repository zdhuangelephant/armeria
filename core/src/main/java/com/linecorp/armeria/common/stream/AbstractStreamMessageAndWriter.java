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

package com.linecorp.armeria.common.stream;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;

abstract class AbstractStreamMessageAndWriter<T> extends AbstractStreamMessage<T>
        implements StreamMessageAndWriter<T> {

    enum State {
        /**
         * The initial state. Will enter {@link #CLOSED} or {@link #CLEANUP}.
         * 初始状态，此状态会切换到{@link #CLOSED}，或者{@link #CLEANUP}
         */
        OPEN,
        /**
         * {@link #close()} or {@link #close(Throwable)} has been called. Will enter {@link #CLEANUP} after
         * {@link Subscriber#onComplete()} or {@link Subscriber#onError(Throwable)} is invoked.
         * {@link #close()} or {@link #close(Throwable)}已经被调用过。在{@link Subscriber#onComplete()} or {@link Subscriber#onError(Throwable)}被调用后，将会切换到{@link #CLEANUP}
         */
        CLOSED,
        /**
         * Anything in the queue must be cleaned up.
         * Enters this state when there's no chance of consumption by subscriber.
         * i.e. when any of the following methods are invoked:
         * 在队列内的任何元素都会被清空。
         * 当订阅者没有了可消费的报文的时候，将会进入{@link #CLEANUP}。eg：如下方法被调用后：
         * <ul>
         *   <li>{@link Subscription#cancel()}</li>
         *   <li>{@link #abort()} (via {@link AbortingSubscriber})</li>
         *   <li>{@link Subscriber#onComplete()}</li>
         *   <li>{@link Subscriber#onError(Throwable)}</li>
         * </ul>
         */
        CLEANUP
    }

    @Override
    public boolean tryWrite(T obj) {
        requireNonNull(obj, "obj");
        if (obj instanceof ReferenceCounted) {
            ((ReferenceCounted) obj).touch();
            // 所写的对象及obj一定是ByteBufHolder类型，或者ByteBuf类型。否则抛异常。
            if (!(obj instanceof ByteBufHolder) && !(obj instanceof ByteBuf)) {
                throw new IllegalArgumentException(
                        "can't publish a ReferenceCounted that's not a ByteBuf or a ByteBufHolder: " + obj);
            }
        }


        if (!isOpen()) {
            // 如果Stream对象已经关闭了,则释放掉内存的引用，顺便把计数器归为0；
            ReferenceCountUtil.safeRelease(obj);
            return false;
        }

        addObject(obj);
        return true;
    }

    @Override
    public CompletableFuture<Void> onDemand(Runnable task) {
        requireNonNull(task, "task");

        final AwaitDemandFuture f = new AwaitDemandFuture();
        if (!isOpen()) {
            f.completeExceptionally(ClosedPublisherException.get());
            return f;
        }

        addObjectOrEvent(f);
        return f.thenRun(task);
    }

    /**
     * Adds an object to publish to the stream.
     * <br/>
     * 向这个流内再补充一个obj，其实是往队列内推入一个元素。
     */
    abstract void addObject(T obj);

    /**
     * Adds an object to publish (of type {@code T} or an event (e.g., {@link CloseEvent},
     * {@link AwaitDemandFuture}) to the stream.
     * <br/>
     * 向这个流内，添加一个obj或CloseEvent或AwaitDemandFuture。
     */
    abstract void addObjectOrEvent(Object obj);

    static final class AwaitDemandFuture extends CompletableFuture<Void> {}
}
