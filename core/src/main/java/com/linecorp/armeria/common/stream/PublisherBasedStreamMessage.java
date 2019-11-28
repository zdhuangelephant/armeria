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

import static com.linecorp.armeria.common.stream.StreamMessageUtil.abortedOrLate;
import static com.linecorp.armeria.common.stream.StreamMessageUtil.containsNotifyCancellation;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import javax.annotation.Nullable;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.annotations.VisibleForTesting;
import com.spotify.futures.CompletableFutures;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.RequestContext;

import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.ImmediateEventExecutor;

/**
 * Adapts a {@link Publisher} into a {@link StreamMessage}.
 * <br/>
 *  一个Publisher的适配器
 *
 * @param <T> the type of element signaled 要发送的数据体
 */
public class PublisherBasedStreamMessage<T> implements StreamMessage<T> {

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<PublisherBasedStreamMessage, AbortableSubscriber>
            subscriberUpdater = AtomicReferenceFieldUpdater.newUpdater(
            PublisherBasedStreamMessage.class, AbortableSubscriber.class, "subscriber");

    private final Publisher<? extends T> publisher;
    private final CompletableFuture<Void> completionFuture = new CompletableFuture<>();
    @Nullable
    @SuppressWarnings("unused") // Updated only via subscriberUpdater.
    private volatile AbortableSubscriber subscriber;
    private volatile boolean publishedAny;

    /**
     * Creates a new instance with the specified delegate {@link Publisher}.
     */
    public PublisherBasedStreamMessage(Publisher<? extends T> publisher) {
        this.publisher = publisher;
    }

    /**
     * Returns the delegate {@link Publisher}.
     */
    protected final Publisher<? extends T> delegate() {
        return publisher;
    }

    @Override
    public boolean isOpen() {
        return !completionFuture.isDone();
    }

    @Override
    public boolean isEmpty() {
        return !isOpen() && !publishedAny;
    }

    @Override
    public final void subscribe(Subscriber<? super T> subscriber) {
        subscribe(subscriber, defaultSubscriberExecutor());
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber, boolean withPooledObjects) {
        subscribe0(subscriber, defaultSubscriberExecutor(), false);
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber, SubscriptionOption... options) {
        requireNonNull(options, "options");

        final boolean notifyCancellation = containsNotifyCancellation(options);
        subscribe0(subscriber, defaultSubscriberExecutor(), notifyCancellation);
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber, EventExecutor executor) {
        subscribe0(subscriber, executor, false);
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber, EventExecutor executor, boolean withPooledObjects) {
        subscribe0(subscriber, executor, false);
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber, EventExecutor executor,
                          SubscriptionOption... options) {
        requireNonNull(options, "options");

        final boolean notifyCancellation = containsNotifyCancellation(options);
        subscribe0(subscriber, executor, notifyCancellation);
    }

    private void subscribe0(Subscriber<? super T> subscriber, EventExecutor executor,
                           boolean notifyCancellation) {
        requireNonNull(subscriber, "subscriber");
        requireNonNull(executor, "executor");

        if (!subscribe1(subscriber, executor, notifyCancellation)) {
            final AbortableSubscriber oldSubscriber = this.subscriber;
            assert oldSubscriber != null;
            failLateSubscriber(executor, subscriber, oldSubscriber.subscriber);
        }
    }

    /**
     * Returns the default {@link EventExecutor} which will be used when a user subscribes using
     * {@link #subscribe(Subscriber, SubscriptionOption...)}.
     */
    protected EventExecutor defaultSubscriberExecutor() {
        return RequestContext.mapCurrent(RequestContext::eventLoop, () -> CommonPools.workerGroup().next());
    }

    private boolean subscribe1(Subscriber<? super T> subscriber, EventExecutor executor,
                               boolean notifyCancellation) {
        final AbortableSubscriber s = new AbortableSubscriber(this, subscriber, executor, notifyCancellation);
        if (!subscriberUpdater.compareAndSet(this, null, s)) {
            return false;
        }

        publisher.subscribe(s);

        return true;
    }

    private static void failLateSubscriber(EventExecutor executor,
                                           Subscriber<?> lateSubscriber, Subscriber<?> oldSubscriber) {
        final Throwable cause = abortedOrLate(oldSubscriber);

        executor.execute(() -> {
            lateSubscriber.onSubscribe(NoopSubscription.INSTANCE);
            lateSubscriber.onError(cause);
        });
    }

    @Override
    public CompletableFuture<List<T>> drainAll() {
        return drainAll(defaultSubscriberExecutor());
    }

    @Override
    public CompletableFuture<List<T>> drainAll(boolean withPooledObjects) {
        return drainAll(defaultSubscriberExecutor());
    }

    @Override
    public CompletableFuture<List<T>> drainAll(SubscriptionOption... options) {
        return drainAll(defaultSubscriberExecutor());
    }

    @Override
    public CompletableFuture<List<T>> drainAll(EventExecutor executor) {
        requireNonNull(executor, "executor");

        final StreamMessageDrainer<T> drainer = new StreamMessageDrainer<>(false);
        if (!subscribe1(drainer, executor, false)) {
            final AbortableSubscriber subscriber = this.subscriber;
            assert subscriber != null;
            return CompletableFutures.exceptionallyCompletedFuture(abortedOrLate(subscriber.subscriber));
        }

        return drainer.future();
    }

    @Override
    public CompletableFuture<List<T>> drainAll(EventExecutor executor, boolean withPooledObjects) {
        return drainAll(executor);
    }

    @Override
    public CompletableFuture<List<T>> drainAll(EventExecutor executor, SubscriptionOption... options) {
        requireNonNull(options, "options");

        return drainAll(executor);
    }

    @Override
    public void abort() {
        final AbortableSubscriber subscriber = this.subscriber;
        if (subscriber != null) {
            subscriber.abort();
            return;
        }

        final AbortableSubscriber abortable = new AbortableSubscriber(this, AbortingSubscriber.get(),
                                                                      ImmediateEventExecutor.INSTANCE,
                                                                      false);
        if (!subscriberUpdater.compareAndSet(this, null, abortable)) {
            this.subscriber.abort();
            return;
        }

        abortable.abort();
        abortable.onSubscribe(NoopSubscription.INSTANCE);
    }

    @Override
    public CompletableFuture<Void> completionFuture() {
        return completionFuture;
    }


    /**
     * 一个订阅者和订阅关系的实现结合体[可终止的]
     */
    @VisibleForTesting
    static final class AbortableSubscriber implements Subscriber<Object>, Subscription {
        // 该订阅者的父极PublisherBasedStreamMessage
        private final PublisherBasedStreamMessage<?> parent;
        // 线程池
        private final EventExecutor executor;
        // 通知取消的状态位
        private boolean notifyCancellation;
        // 真正的订阅者
        private Subscriber<Object> subscriber;
        // 种植等待
        private volatile boolean abortPending;
        // 订阅关系引用
        @Nullable
        private volatile Subscription subscription;

        @SuppressWarnings("unchecked")
        AbortableSubscriber(PublisherBasedStreamMessage<?> parent, Subscriber<?> subscriber,
                            EventExecutor executor, boolean notifyCancellation) {
            this.parent = parent;
            this.subscriber = (Subscriber<Object>) subscriber;
            this.executor = executor;
            this.notifyCancellation = notifyCancellation;
        }

        /**
         * 发送消息
         * @param n
         */
        @Override
        public void request(long n) {
            final Subscription subscription = this.subscription;
            assert subscription != null;
            subscription.request(n);
        }

        /**
         *
         */
        @Override
        public void cancel() {
            // 'subscription' can never be null here because 'subscriber.onSubscriber()' is invoked
            // only after 'subscription' is set. See onSubscribe0().
            assert subscription != null;

            // Don't cancel but just abort if abort is pending.
            cancelOrAbort(!abortPending);
        }

        void abort() {
            abortPending = true;
            if (subscription != null) {
                cancelOrAbort(false);
            }
        }

        private void cancelOrAbort(boolean cancel) {
            if (executor.inEventLoop()) {
                cancelOrAbort0(cancel);
            } else {
                executor.execute(() -> cancelOrAbort0(cancel));
            }
        }

        private void cancelOrAbort0(boolean cancel) {
            final CompletableFuture<Void> completionFuture = parent.completionFuture();
            if (completionFuture.isDone()) {
                return;
            }

            final Subscriber<Object> subscriber = this.subscriber;
            // Replace the subscriber with a placeholder so that it can be garbage-collected and
            // we conform to the Reactive Streams specification rule 3.13.
            if (!(subscriber instanceof AbortingSubscriber)) {
                this.subscriber = NoopSubscriber.get();
            }

            final Throwable cause = cancel ? CancelledSubscriptionException.get()
                                           : AbortedStreamException.get();
            try {
                if (!cancel || notifyCancellation) {
                    subscriber.onError(cause);
                }
            } finally {
                try {
                    subscription.cancel();
                } finally {
                    completionFuture.completeExceptionally(cause);
                }
            }
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            if (executor.inEventLoop()) {
                onSubscribe0(subscription);
            } else {
                executor.execute(() -> onSubscribe0(subscription));
            }
        }

        private void onSubscribe0(Subscription subscription) {
            try {
                this.subscription = subscription;
                subscriber.onSubscribe(this);
            } finally {
                if (abortPending) {
                    cancelOrAbort0(false);
                }
            }
        }

        @Override
        public void onNext(Object obj) {
            parent.publishedAny = true;
            if (executor.inEventLoop()) {
                subscriber.onNext(obj);
            } else {
                executor.execute(() -> subscriber.onNext(obj));
            }
        }

        @Override
        public void onError(Throwable cause) {
            if (executor.inEventLoop()) {
                onError0(cause);
            } else {
                executor.execute(() -> onError0(cause));
            }
        }

        private void onError0(Throwable cause) {
            try {
                subscriber.onError(cause);
            } finally {
                parent.completionFuture().completeExceptionally(cause);
            }
        }

        @Override
        public void onComplete() {
            if (executor.inEventLoop()) {
                onComplete0();
            } else {
                executor.execute(this::onComplete0);
            }
        }

        private void onComplete0() {
            try {
                subscriber.onComplete();
            } finally {
                parent.completionFuture().complete(null);
            }
        }
    }
}
