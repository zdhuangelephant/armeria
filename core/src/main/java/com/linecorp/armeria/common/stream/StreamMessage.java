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

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.util.ReferenceCounted;
import io.netty.util.concurrent.EventExecutor;

/**
 * {@link StreamMessage}不同于常规的{@link Publisher}，{@link StreamMessage}是一个仅允许一个订阅者，并且仅传输一次
 *
 * A variant of <a href="http://www.reactive-streams.org/">Reactive Streams</a> {@link Publisher}, which allows
 * only one {@link Subscriber}. Unlike a usual {@link Publisher}, a {@link StreamMessage} can stream itself
 * only once.  It has the following additional operations on top of what the Reactive Streams API provides:
 * <ul>
 *   <li>{@link #isOpen()}</li>
 *   <li>{@link #isEmpty()}</li>
 *   <li>{@link #completionFuture()}</li>
 *   <li>{@link #abort()}</li>
 * </ul>
 *
 * <h3>When is a {@link StreamMessage} fully consumed?</h3>
 *
 * <p>A {@link StreamMessage} is <em>complete</em> (or 'fully consumed') when:
 * <ul>
 *   <li>the {@link Subscriber} consumes all elements and {@link Subscriber#onComplete()} is invoked,</li>
 *   <li>an error occurred and {@link Subscriber#onError(Throwable)} is invoked,</li>
 *   <li>the {@link Subscription} has been cancelled or</li>
 *   <li>{@link #abort()} has been requested.</li>
 * </ul>
 *
 * <p>When fully consumed, the {@link CompletableFuture} returned by {@link StreamMessage#completionFuture()}
 * will complete, which you may find useful because {@link Subscriber} does not notify you when a stream is
 * {@linkplain Subscription#cancel() cancelled}.
 *
 * <h3>Publication and Consumption of {@link ReferenceCounted} objects</h3>
 *
 * <p>{@link StreamMessage} will reject the publication request of a {@link ReferenceCounted} object except
 * {@link ByteBuf} and {@link ByteBufHolder}.
 *
 * <p>{@link StreamMessage} will discard the publication request of a {@link ByteBuf} or a {@link ByteBufHolder}
 * silently and release it automatically when the publication is attempted after the stream is closed.
 *
 * <p>For {@link ByteBuf} and {@link ByteBufHolder}, {@link StreamMessage} will convert them into their
 * respective unpooled versions that never leak, so that the {@link Subscriber} does not need to worry about
 * leaks.
 *
 * <p>If a {@link Subscriber} does not want a {@link StreamMessage} to make a copy of a {@link ByteBufHolder},
 * specify {@link SubscriptionOption#WITH_POOLED_OBJECTS} when you subscribe. Note that the {@link Subscriber}
 * is responsible for releasing the objects given with {@link Subscriber#onNext(Object)}.
 *
 * <p>{@link Subscriber#onError(Throwable)} is invoked when any exception is raised except the
 * {@link CancelledSubscriptionException} which is caused by {@link Subscription#cancel()}. If you want your
 * {@link Subscriber} get notified by {@link Subscriber#onError(Throwable)} when {@link Subscription#cancel()}
 * is called, specify {@link SubscriptionOption#NOTIFY_CANCELLATION} when you subscribe.
 *
 * @param <T> the type of element signaled
 */
public interface StreamMessage<T> extends Publisher<T> {
    /**
     * Creates a new {@link StreamMessage} that will publish no objects, just a close event.
     */
    static <T> StreamMessage<T> of() {
        return new EmptyFixedStreamMessage<>();
    }

    /**
     * Creates a new {@link StreamMessage} that will publish the single {@code obj}.
     */
    static <T> StreamMessage<T> of(T obj) {
        requireNonNull(obj, "obj");
        return new OneElementFixedStreamMessage<>(obj);
    }

    /**
     * Creates a new {@link StreamMessage} that will publish the two {@code obj1} and {@code obj2}.
     */
    static <T> StreamMessage<T> of(T obj1, T obj2) {
        requireNonNull(obj1, "obj1");
        requireNonNull(obj2, "obj2");
        return new TwoElementFixedStreamMessage<>(obj1, obj2);
    }

    /**
     * Creates a new {@link StreamMessage} that will publish the given {@code objs}.
     */
    @SafeVarargs
    static <T> StreamMessage<T> of(T... objs) {
        requireNonNull(objs, "objs");
        switch (objs.length) {
            case 0:
                return of();
            case 1:
                return of(objs[0]);
            case 2:
                return of(objs[0], objs[1]);
            default:
                for (int i = 0; i < objs.length; i++) {
                    if (objs[i] == null) {
                        throw new NullPointerException("objs[" + i + "] is null");
                    }
                }
                return new RegularFixedStreamMessage<>(objs);
        }
    }

    /**
     * Returns {@code true} if this stream is not closed yet. Note that a stream may not be
     * {@linkplain #completionFuture() complete} even if it's closed; a stream is complete when it's fully
     * consumed by a {@link Subscriber}.
     */
    boolean isOpen();

    /**
     * Returns {@code true} if this stream has been closed and did not publish any elements.
     * Note that this method will not return {@code true} when the stream is open even if it has not
     * published anything so far, because it may publish something later.
     */
    boolean isEmpty();

    /**
     * Returns {@code true} if this stream is complete, either successfully or exceptionally,
     * including cancellation and abortion.
     *
     * <p>A {@link StreamMessage} is <em>complete</em> (or 'fully consumed') when:
     * <ul>
     *   <li>the {@link Subscriber} consumes all elements and {@link Subscriber#onComplete()} is invoked,</li>
     *   <li>an error occurred and {@link Subscriber#onError(Throwable)} is invoked,</li>
     *   <li>the {@link Subscription} has been cancelled or</li>
     *   <li>{@link #abort()} has been requested.</li>
     * </ul>
     */
    default boolean isComplete() {
        return completionFuture().isDone();
    }

    /**
     * Returns a {@link CompletableFuture} that completes when this stream is complete,
     * either successfully or exceptionally, including cancellation and abortion.
     * <br/>
     * 返回一个Future。这个Future是stream完成后的结果对象。成功或异常。包含了取消异常和中断异常。
     *
     * <p>A {@link StreamMessage} is <em>complete</em>
     * (or 'fully consumed') when:
     * <ul>
     *   <li>the {@link Subscriber} consumes all elements and {@link Subscriber#onComplete()} is invoked,</li>
     *   <li>an error occurred and {@link Subscriber#onError(Throwable)} is invoked,</li>
     *   <li>the {@link Subscription} has been cancelled or</li>
     *   <li>{@link #abort()} has been requested.</li>
     * </ul>
     */
    CompletableFuture<Void> completionFuture();

    /**
     * Returns a {@link CompletableFuture} that completes when this stream is complete,
     * either successfully or exceptionally, including cancellation and abortion.
     *
     * @deprecated Use {@link #completionFuture()} instead.
     */
    @Deprecated
    default CompletableFuture<Void> closeFuture() {
        return completionFuture();
    }

    /**
     * Requests to start streaming data to the specified {@link Subscriber}. If there is a problem subscribing,
     * {@link Subscriber#onError(Throwable)} will be invoked with one of the following exceptions:
     * <ul>
     *   <li>{@link IllegalStateException} if other {@link Subscriber} subscribed to this stream already.</li>
     *   <li>{@link AbortedStreamException} if this stream has been {@linkplain #abort() aborted}.</li>
     *   <li>{@link CancelledSubscriptionException} if this stream has been
     *       {@linkplain Subscription#cancel() cancelled} and {@link SubscriptionOption#NOTIFY_CANCELLATION} is
     *       specified when subscribed.</li>
     *   <li>Other exceptions that occurred due to an error while retrieving the elements.</li>
     * </ul>
     * <br/>
     * 将一个数据流传送到指定的Subscriber。如果传送过程中发生问题。则{@link Subscriber#onError(Throwable)}将会被回调。
     */
    @Override
    void subscribe(Subscriber<? super T> subscriber);

    /**
     * Requests to start streaming data to the specified {@link Subscriber}. If there is a problem subscribing,
     * {@link Subscriber#onError(Throwable)} will be invoked with one of the following exceptions:
     * <ul>
     *   <li>{@link IllegalStateException} if other {@link Subscriber} subscribed to this stream already.</li>
     *   <li>{@link AbortedStreamException} if this stream has been {@linkplain #abort() aborted}.</li>
     *   <li>{@link CancelledSubscriptionException} if this stream has been
     *       {@linkplain Subscription#cancel() cancelled} and {@link SubscriptionOption#NOTIFY_CANCELLATION} is
     *       specified when subscribed.</li>
     *   <li>Other exceptions that occurred due to an error while retrieving the elements.</li>
     * </ul>
     *
     * @param options {@link SubscriptionOption}s to subscribe with
     */
    void subscribe(Subscriber<? super T> subscriber, SubscriptionOption... options);

    /**
     * Requests to start streaming data to the specified {@link Subscriber}. If there is a problem subscribing,
     * {@link Subscriber#onError(Throwable)} will be invoked with one of the following exceptions:
     * <ul>
     *   <li>{@link IllegalStateException} if other {@link Subscriber} subscribed to this stream already.</li>
     *   <li>{@link AbortedStreamException} if this stream has been {@linkplain #abort() aborted}.</li>
     *   <li>{@link CancelledSubscriptionException} if this stream has been
     *       {@linkplain Subscription#cancel() cancelled} and {@link SubscriptionOption#NOTIFY_CANCELLATION} is
     *       specified when subscribed.</li>
     *   <li>Other exceptions that occurred due to an error while retrieving the elements.</li>
     * </ul>
     *
     * @param executor the executor to subscribe
     */
    void subscribe(Subscriber<? super T> subscriber, EventExecutor executor);

    /**
     * Requests to start streaming data to the specified {@link Subscriber}. If there is a problem subscribing,
     * {@link Subscriber#onError(Throwable)} will be invoked with one of the following exceptions:
     * <ul>
     *   <li>{@link IllegalStateException} if other {@link Subscriber} subscribed to this stream already.</li>
     *   <li>{@link AbortedStreamException} if this stream has been {@linkplain #abort() aborted}.</li>
     *   <li>{@link CancelledSubscriptionException} if this stream has been
     *       {@linkplain Subscription#cancel() cancelled} and {@link SubscriptionOption#NOTIFY_CANCELLATION} is
     *       specified when subscribed.</li>
     *   <li>Other exceptions that occurred due to an error while retrieving the elements.</li>
     * </ul>
     *
     * @param executor the executor to subscribe
     * @param options {@link SubscriptionOption}s to subscribe with
     */
    void subscribe(Subscriber<? super T> subscriber, EventExecutor executor, SubscriptionOption... options);

    /**
     * Requests to start streaming data to the specified {@link Subscriber}. If there is a problem subscribing,
     * {@link Subscriber#onError(Throwable)} will be invoked with one of the following exceptions:
     * <ul>
     *   <li>{@link IllegalStateException} if other {@link Subscriber} subscribed to this stream already.</li>
     *   <li>{@link AbortedStreamException} if this stream has been {@linkplain #abort() aborted}.</li>
     *   <li>{@link CancelledSubscriptionException} if this stream has been
     *       {@linkplain Subscription#cancel() cancelled} and {@link SubscriptionOption#NOTIFY_CANCELLATION} is
     *       specified when subscribed.</li>
     *   <li>Other exceptions that occurred due to an error while retrieving the elements.</li>
     * </ul>
     *
     * @deprecated Use {@link #subscribe(Subscriber, SubscriptionOption...)}.
     */
    @Deprecated
    void subscribe(Subscriber<? super T> subscriber, boolean withPooledObjects);

    /**
     * Requests to start streaming data, invoking the specified {@link Subscriber} from the specified
     * {@link Executor}. If there is a problem subscribing, {@link Subscriber#onError(Throwable)} will be
     * invoked with one of the following exceptions:
     * <ul>
     *   <li>{@link IllegalStateException} if other {@link Subscriber} subscribed to this stream already.</li>
     *   <li>{@link AbortedStreamException} if this stream has been {@linkplain #abort() aborted}.</li>
     *   <li>{@link CancelledSubscriptionException} if this stream has been
     *       {@linkplain Subscription#cancel() cancelled} and {@link SubscriptionOption#NOTIFY_CANCELLATION} is
     *       specified when subscribed.</li>
     *   <li>Other exceptions that occurred due to an error while retrieving the elements.</li>
     * </ul>
     *
     * @deprecated Use {@link #subscribe(Subscriber, EventExecutor, SubscriptionOption...)}.
     *
     * @param executor the executor to subscribe
     */
    @Deprecated
    void subscribe(Subscriber<? super T> subscriber, EventExecutor executor, boolean withPooledObjects);

    /**
     * Subscribes to this {@link StreamMessage} and retrieves all elements from it.
     * The returned {@link CompletableFuture} may be completed exceptionally with the following exceptions:
     * <ul>
     *   <li>{@link IllegalStateException} if other {@link Subscriber} subscribed to this stream already.</li>
     *   <li>{@link AbortedStreamException} if this stream has been {@linkplain #abort() aborted}.</li>
     *   <li>Other exceptions that occurred due to an error while retrieving the elements.</li>
     * </ul>
     *
     * @return the {@link CompletableFuture} which will be completed with the list of the elements retrieved.
     */
    CompletableFuture<List<T>> drainAll();

    /**
     * Subscribes to this {@link StreamMessage} and retrieves all elements from it.
     * The returned {@link CompletableFuture} may be completed exceptionally with the following exceptions:
     * <ul>
     *   <li>{@link IllegalStateException} if other {@link Subscriber} subscribed to this stream already.</li>
     *   <li>{@link AbortedStreamException} if this stream has been {@linkplain #abort() aborted}.</li>
     *   <li>Other exceptions that occurred due to an error while retrieving the elements.</li>
     * </ul>
     *
     * @deprecated Use {@link #drainAll(SubscriptionOption...)}.
     *
     * @return the {@link CompletableFuture} which will be completed with the list of the elements retrieved.
     */
    @Deprecated
    CompletableFuture<List<T>> drainAll(boolean withPooledObjects);

    /**
     * Subscribes to this {@link StreamMessage} and retrieves all elements from it.
     * The returned {@link CompletableFuture} may be completed exceptionally with the following exceptions:
     * <ul>
     *   <li>{@link IllegalStateException} if other {@link Subscriber} subscribed to this stream already.</li>
     *   <li>{@link AbortedStreamException} if this stream has been {@linkplain #abort() aborted}.</li>
     *   <li>Other exceptions that occurred due to an error while retrieving the elements.</li>
     * </ul>
     *
     * @deprecated Use {@link #drainAll(EventExecutor, SubscriptionOption...)}.
     *
     * @param executor the executor to retrieve all elements
     *
     * @return the {@link CompletableFuture} which will be completed with the list of the elements retrieved.
     */
    @Deprecated
    CompletableFuture<List<T>> drainAll(EventExecutor executor, boolean withPooledObjects);

    /**
     * Subscribes to this {@link StreamMessage} and retrieves all elements from it.
     * The returned {@link CompletableFuture} may be completed exceptionally with the following exceptions:
     * <ul>
     *   <li>{@link IllegalStateException} if other {@link Subscriber} subscribed to this stream already.</li>
     *   <li>{@link AbortedStreamException} if this stream has been {@linkplain #abort() aborted}.</li>
     *   <li>Other exceptions that occurred due to an error while retrieving the elements.</li>
     * </ul>
     *
     * @param options {@link SubscriptionOption}s to subscribe with. Note that
     *                {@link SubscriptionOption#NOTIFY_CANCELLATION} is ineffective because there's no
     *                cancelling while draining all elements.
     * @return the {@link CompletableFuture} which will be completed with the list of the elements retrieved.
     */
    CompletableFuture<List<T>> drainAll(SubscriptionOption... options);

    /**
     * Subscribes to this {@link StreamMessage} and retrieves all elements from it.
     * The returned {@link CompletableFuture} may be completed exceptionally with the following exceptions:
     * <ul>
     *   <li>{@link IllegalStateException} if other {@link Subscriber} subscribed to this stream already.</li>
     *   <li>{@link AbortedStreamException} if this stream has been {@linkplain #abort() aborted}.</li>
     *   <li>Other exceptions that occurred due to an error while retrieving the elements.</li>
     * </ul>
     *
     * @param executor the executor to retrieve all elements
     * @return the {@link CompletableFuture} which will be completed with the list of the elements retrieved.
     */
    CompletableFuture<List<T>> drainAll(EventExecutor executor);

    /**
     * Subscribes to this {@link StreamMessage} and retrieves all elements from it.
     * The returned {@link CompletableFuture} may be completed exceptionally with the following exceptions:
     * <ul>
     *   <li>{@link IllegalStateException} if other {@link Subscriber} subscribed to this stream already.</li>
     *   <li>{@link AbortedStreamException} if this stream has been {@linkplain #abort() aborted}.</li>
     *   <li>Other exceptions that occurred due to an error while retrieving the elements.</li>
     * </ul>
     *
     * @param executor the executor to retrieve all elements
     * @param options {@link SubscriptionOption}s to subscribe with. Note that
     *                {@link SubscriptionOption#NOTIFY_CANCELLATION} is ineffective because there's no
     *                cancelling while draining all elements.
     * @return the {@link CompletableFuture} which will be completed with the list of the elements retrieved.
     */
    CompletableFuture<List<T>> drainAll(EventExecutor executor, SubscriptionOption... options);

    /**
     * Closes this stream with {@link AbortedStreamException} and prevents further subscription.
     * A {@link Subscriber} that attempts to subscribe to an aborted stream will be notified with
     * an {@link AbortedStreamException} via {@link Subscriber#onError(Throwable)}. Calling this method
     * on a closed or aborted stream has no effect.
     * <br/>
     * 用AbortedStreamException关闭流对象，并且阻止其他的的订阅关系的建立。如果一个订阅者尝试订阅已经被中断的流对象，将会抛出AbortedStreamException。
     */
    void abort();
}
