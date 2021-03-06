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

package com.linecorp.armeria.common.stream;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import javax.annotation.CheckReturnValue;

import org.reactivestreams.Subscriber;

import com.linecorp.armeria.unsafe.ByteBufHttpData;

import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCounted;

/**
 * 这就是Stream(即流)
 * Produces the objects to be published by a {@link StreamMessage}.
 * <br/>
 * 产生，要被{@link StreamMessage}的实现类发布出去的object。
 *
 * <h3 id="reference-counted">Life cycle of reference-counted objects</h3>
 *
 * <p>When the following methods are given with a {@link ReferenceCounted} object, such as {@link ByteBuf} and
 * {@link ByteBufHttpData}, or the {@link Supplier} that provides such an object:
 *
 * <ul>
 *   <li>{@link #tryWrite(Object)}</li>
 *   <li>{@link #tryWrite(Supplier)}</li>
 *   <li>{@link #write(Object)}</li>
 *   <li>{@link #write(Supplier)}</li>
 * </ul>
 * 当object不会再被使用时，object会自动的被当前流给释放掉，如下场景:
 * the object will be released automatically by the stream when it's no longer in use, such as when:
 * <ul>
 *   <li>The method returns {@code false} or raises an exception. 返回false，或抛出异常</li>
 *   <li>The {@link Subscriber} of the stream consumes it. 这个流的订阅者消费了它</li>
 *   <li>The stream is cancelled, aborted or failed. 这个流被取消或中断或失败</li>
 * </ul>
 *
 * @param <T> the type of the stream element
 */
public interface StreamWriter<T> {

    /**
     * Returns {@code true} if the {@link StreamMessage} is open.
     * <br/>
     * 如果该实例open状态，返回true
     */
    boolean isOpen();

    /**
     * Writes the specified object to the {@link StreamMessage}. The written object will be transferred to the
     * {@link Subscriber}.
     * <br/>
     * 将指定的对象写入该{@link StreamMessage}, 并且T o，将会被传递到Subscriber
     *
     * @throws ClosedPublisherException if the stream was already closed
     * @throws IllegalArgumentException if the publication of the specified object has been rejected
     * @see <a href="#reference-counted">Life cycle of reference-counted objects</a>
     */
    default void write(T o) {
        if (!tryWrite(o)) {
            throw ClosedPublisherException.get();
        }
    }

    /**
     * Writes the specified object {@link Supplier} to the {@link StreamMessage}. The object provided by the
     * {@link Supplier} will be transferred to the {@link Subscriber}.
     * <br/>
     * 将指定的Supplier对象的get()后的值写入该{@link StreamMessage}, 并且Supplier对象的get()后的值，将会被传递到Subscriber
     *
     * @throws ClosedPublisherException if the stream was already closed. 如果这个流已经被关闭则会抛出ClosedPublisherException
     * @see <a href="#reference-counted">Life cycle of reference-counted objects</a>
     */
    default void write(Supplier<? extends T> o) {
        if (!tryWrite(o)) {
            throw ClosedPublisherException.get();
        }
    }

    /**
     * Writes the specified object to the {@link StreamMessage}. The written object will be transferred to the
     * {@link Subscriber}.
     * <br/>
     * 将指定的obj写入StreamMessage(即Publisher)，这个obj将会被传递到Subscriber(订阅者)
     *
     * @return {@code true} if the specified object has been scheduled for publication. {@code false} if the
     *         stream has been closed already. <br/> true，如果这个obj已经被安排到发布的过程; false，如果这个流已经被关闭。
     *
     * @throws IllegalArgumentException if the publication of the specified object has been rejected
     * @see <a href="#reference-counted">Life cycle of reference-counted objects</a>
     */
    @CheckReturnValue
    boolean tryWrite(T o);

    /**
     * Writes the specified object {@link Supplier} to the {@link StreamMessage}. The object provided by the
     * {@link Supplier} will be transferred to the {@link Subscriber}.
     *
     * @return {@code true} if the specified object has been scheduled for publication. {@code false} if the
     *         stream has been closed already.
     * @see <a href="#reference-counted">Life cycle of reference-counted objects</a>
     */
    @CheckReturnValue
    default boolean tryWrite(Supplier<? extends T> o) {
        return tryWrite(o.get());
    }

    /**
     * Performs the specified {@code task} when there are enough demands from the {@link Subscriber}.
     *<br/>
     * 当从订阅者有足够的需求时，执行特定的task。
     * @return the future that completes successfully when the {@code task} finishes or
     *         exceptionally when the {@link StreamMessage} is closed unexpectedly.
     */
    CompletableFuture<Void> onDemand(Runnable task);

    /**
     * Closes the {@link StreamMessage} successfully. {@link Subscriber#onComplete()} will be invoked to
     * signal that the {@link Subscriber} has consumed the stream completely.
     * <br/>
     * 成功的关闭该{@link StreamMessage}，{@link Subscriber#onComplete()}会被回调来通知Subscriber当前流已经被成功消费。
     */
    void close();

    /**
     * Closes the {@link StreamMessage} exceptionally. {@link Subscriber#onError(Throwable)} will be invoked to
     * signal that the {@link Subscriber} did not consume the stream completely.
     * <br/>
     * 异常的关闭该{@link StreamMessage}，{@link Subscriber#onError(Throwable)} 会被回调来通知Subscriber没有消费掉当前流。
     */
    void close(Throwable cause);

    /**
     * Writes the given object and closes the stream successfully.
     * <br/>
     * 写入一个obj并且关闭当前流
     */
    default void close(T obj) {
        write(obj);
        close();
    }
}
