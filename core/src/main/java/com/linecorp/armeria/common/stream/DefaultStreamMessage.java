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

import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import javax.annotation.Nullable;

import org.jctools.queues.MpscChunkedArrayQueue;
import org.reactivestreams.Subscriber;

import com.linecorp.armeria.common.Flags;

import io.netty.util.concurrent.ImmediateEventExecutor;

/**
 * A {@link StreamMessage} which buffers the elements to be signaled into a {@link Queue}.
 * <br/>
 * 本质是一个StreamMessage(即Publisher)和StreamWriter的复合体，它可以缓存即将要发送的msg，并且将msgs放入{@link Queue}
 *
 * <p>This class implements the {@link StreamWriter} interface as well. A written element will be buffered
 * into the {@link Queue} until a {@link Subscriber} consumes it. Use {@link StreamWriter#onDemand(Runnable)}
 * to control the rate of production so that the {@link Queue} does not grow up infinitely.
 * <br/>
 * 这个类实现了StreamWriter接口。被写入的元素将会被enqueue到队列内，直到Subscriber能够消费它。
 * 通过{@link StreamWriter#onDemand(Runnable)}方法可以控制生产消息的速率，以至于队列的长度不会无限制的增长。
 *
 * <pre>{@code
 * void stream(QueueBasedPublished<Integer> pub, int start, int end) {
 *     // Write 100 integers at most.
 *     int actualEnd = (int) Math.min(end, start + 100L);
 *     int i;
 *     for (i = start; i < actualEnd; i++) {
 *         pub.write(i);
 *     }
 *
 *     if (i == end) {
 *         // Wrote the last element.
 *         return;
 *     }
 *
 *     pub.onDemand(() -> stream(pub, i, end));
 * }
 *
 * final QueueBasedPublisher<Integer> myPub = new QueueBasedPublisher<>();
 * stream(myPub, 0, Integer.MAX_VALUE);
 * }</pre>
 *
 * @param <T> the type of element signaled
 */
public class DefaultStreamMessage<T> extends AbstractStreamMessageAndWriter<T> {

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<DefaultStreamMessage, SubscriptionImpl>
            subscriptionUpdater = AtomicReferenceFieldUpdater.newUpdater(
            DefaultStreamMessage.class, SubscriptionImpl.class, "subscription");

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<DefaultStreamMessage, State> stateUpdater =
            AtomicReferenceFieldUpdater.newUpdater(DefaultStreamMessage.class, State.class, "state");

    // StreamWriter先将元素写入队列
    private final Queue<Object> queue;

    @Nullable
    @SuppressWarnings("unused")
    private volatile SubscriptionImpl subscription; // set only via subscriptionUpdater

    private long demand; // set only when in the subscriber thread。 仅仅只能在订阅者的线程内进行设置改值。

    @SuppressWarnings("FieldMayBeFinal")
    private volatile State state = State.OPEN;

    private volatile boolean wroteAny;

    private boolean inOnNext;
    private boolean invokedOnSubscribe;

    /**
     * Creates a new instance.
     */
    public DefaultStreamMessage() {
        queue = new MpscChunkedArrayQueue<>(32, 1 << 30);
    }

    @Override
    public boolean isOpen() {
        return state == State.OPEN;
    }

    @Override
    public boolean isEmpty() {
        return !isOpen() && !wroteAny;
    }

    @Override
    SubscriptionImpl subscribe(SubscriptionImpl subscription) {
        if (!subscriptionUpdater.compareAndSet(this, null, subscription)) {
            final SubscriptionImpl oldSubscription = this.subscription;
            assert oldSubscription != null;
            return oldSubscription;
        }

        final Subscriber<Object> subscriber = subscription.subscriber();
        if (subscription.needsDirectInvocation()) {
            invokedOnSubscribe = true;
            /**
             * Invoked after calling Publisher.subscribe(Subscriber).
             * No data will start flowing until Subscription.request(long) is invoked.
             * It is the responsibility of this Subscriber instance to call Subscription.request(long) whenever more data is wanted.
             * The Publisher will send notifications only in response to Subscription.request(long).
             * <br/>
             * 在Publisher.subscribe(Subscriber)调用后会接着调用此方法。
             * 如果Subscription.request(long)没有被调用。则就不会有数据流动。
             * Subscriber的职责是调用Subscription.request(long)，无论已经有多少数据储量。
             * Publisher仅仅只会对Subscription.request(long)做出响应。
             */
            subscriber.onSubscribe(subscription);
        } else {
            subscription.executor().execute(() -> {
                invokedOnSubscribe = true;
                subscriber.onSubscribe(subscription);
            });
        }

        return subscription;
    }

    @Override
    public void abort() {
        final SubscriptionImpl currentSubscription = subscription;
        if (currentSubscription != null) {
            cancelOrAbort(false);
            return;
        }

        final SubscriptionImpl newSubscription = new SubscriptionImpl(
                this, AbortingSubscriber.get(), ImmediateEventExecutor.INSTANCE, false, false);
        if (subscriptionUpdater.compareAndSet(this, null, newSubscription)) {
            // We don't need to invoke onSubscribe() for AbortingSubscriber because it's just a placeholder.
            invokedOnSubscribe = true;
        }
        cancelOrAbort(false);
    }

    @Override
    void addObject(T obj) {
        wroteAny = true;
        addObjectOrEvent(obj);
    }

    @Override
    long demand() {
        return demand;
    }

    @Override
    void request(long n) {
        final SubscriptionImpl subscription = this.subscription;
        // A user cannot access subscription without subscribing.
        assert subscription != null;

        if (subscription.needsDirectInvocation()) {
            doRequest(n);
        } else {
            subscription.executor().execute(() -> doRequest(n));
        }
    }

    private void doRequest(long n) {
        final long oldDemand = demand;
        if (oldDemand >= Long.MAX_VALUE - n) {
            demand = Long.MAX_VALUE;
        } else {
            demand = oldDemand + n;
        }

        if (oldDemand == 0 && !queue.isEmpty()) {
            notifySubscriber0();
        }
    }

    @Override
    void cancel() {
        cancelOrAbort(true);
    }

    @Override
    void notifySubscriberOfCloseEvent(SubscriptionImpl subscription, CloseEvent event) {
        // Always called from the subscriber thread.
        try {
            event.notifySubscriber(subscription, completionFuture());
        } finally {
            subscription.clearSubscriber();
            cleanup();
        }
    }

    private void cancelOrAbort(boolean cancel) {
        if (setState(State.OPEN, State.CLEANUP)) {
            final CloseEvent closeEvent;
            if (cancel) {
                closeEvent = Flags.verboseExceptions() ?
                             new CloseEvent(CancelledSubscriptionException.get()) : CANCELLED_CLOSE;
            } else {
                closeEvent = Flags.verboseExceptions() ?
                             new CloseEvent(AbortedStreamException.get()) : ABORTED_CLOSE;
            }
            addObjectOrEvent(closeEvent);
            return;
        }

        switch (state) {
            case CLOSED:
                // close() has been called before cancel(). There's no need to push a CloseEvent,
                // but we need to ensure the completionFuture is notified and any pending objects
                // are removed.
                if (setState(State.CLOSED, State.CLEANUP)) {
                    // TODO(anuraag): Consider pushing a cleanup event instead of serializing the activity
                    // through the event loop.
                    subscription.executor().execute(this::cleanup);
                } else {
                    // Other thread set the state to CLEANUP already and will call cleanup().
                }
                break;
            case CLEANUP:
                // Cleaned up already.
                break;
            default: // OPEN: should never reach here.
                throw new Error();
        }
    }

    @Override
    void addObjectOrEvent(Object obj) {
        queue.add(obj);
        notifySubscriber();
    }

    final void notifySubscriber() {
        final SubscriptionImpl subscription = this.subscription;
        if (subscription == null) {
            return;
        }

        if (queue.isEmpty()) {
            return;
        }

        if (subscription.needsDirectInvocation()) {
            notifySubscriber0();
        } else {
            subscription.executor().execute(this::notifySubscriber0);
        }
    }

    private void notifySubscriber0() {
        if (inOnNext) {
            // Do not let Subscriber.onNext() reenter, because it can lead to weird-looking event ordering
            // for a Subscriber implemented like the following:
            //
            //   public void onNext(Object e) {
            //       subscription.request(1);
            //       ... Handle 'e' ...
            //   }
            //
            // Note that we do not call this method again, because we are already in the notification loop
            // and it will consume the element we've just added in addObjectOrEvent() from the queue as
            // expected.
            //
            // We do not need to worry about synchronizing the access to 'inOnNext' because the subscriber
            // methods must be on the same thread, or synchronized, according to Reactive Streams spec.
            return;
        }

        final SubscriptionImpl subscription = this.subscription;
        if (!invokedOnSubscribe) {
            final Executor executor = subscription.executor();

            // Subscriber.onSubscribe() was not invoked yet.
            // Reschedule the notification so that onSubscribe() is invoked before other events.
            //
            // Note:
            // The rescheduling will occur at most once because the invocation of onSubscribe() must have been
            // scheduled already by subscribe(), given that this.subscription is not null at this point and
            // subscribe() is the only place that sets this.subscription.

            executor.execute(this::notifySubscriber0);
            return;
        }

        for (;;) {
            if (state == State.CLEANUP) {
                cleanup();
                return;
            }

            final Object o = queue.peek();
            if (o == null) {
                break;
            }

            if (o instanceof CloseEvent) {
                handleCloseEvent(subscription, (CloseEvent) queue.remove());
                break;
            }

            if (o instanceof AwaitDemandFuture) {
                if (notifyAwaitDemandFuture()) {
                    // Notified successfully.
                    continue;
                } else {
                    // Not enough demand.
                    break;
                }
            }

            if (!notifySubscriberWithElements(subscription)) {
                // Not enough demand.
                break;
            }
        }
    }

    private boolean notifySubscriberWithElements(SubscriptionImpl subscription) {
        final Subscriber<Object> subscriber = subscription.subscriber();
        if (demand == 0) {
            return false;
        }

        if (demand != Long.MAX_VALUE) {
            demand--;
        }

        @SuppressWarnings("unchecked")
        T o = (T) queue.remove();
        inOnNext = true;
        try {
            o = prepareObjectForNotification(subscription, o);
            subscriber.onNext(o);
        } finally {
            inOnNext = false;
        }
        return true;
    }

    private boolean notifyAwaitDemandFuture() {
        if (demand == 0) {
            return false;
        }

        @SuppressWarnings("unchecked")
        final CompletableFuture<Void> f = (CompletableFuture<Void>) queue.remove();
        f.complete(null);

        return true;
    }

    private void handleCloseEvent(SubscriptionImpl subscription, CloseEvent o) {
        setState(State.OPEN, State.CLEANUP);
        notifySubscriberOfCloseEvent(subscription, o);
    }

    @Override
    public void close() {
        if (setState(State.OPEN, State.CLOSED)) {
            addObjectOrEvent(SUCCESSFUL_CLOSE);
        }
    }

    @Override
    public void close(Throwable cause) {
        requireNonNull(cause, "cause");
        if (cause instanceof CancelledSubscriptionException) {
            throw new IllegalArgumentException("cause: " + cause + " (must use Subscription.cancel())");
        }

        tryClose(cause);
    }

    /**
     * Tries to close the stream with the specified {@code cause}.
     * <p>用参数cause尝试关闭这个流</p>
     *
     * @return {@code true} if the stream has been closed by this method call.
     *         {@code false} if the stream has been closed already by other party.
     */
    protected final boolean tryClose(Throwable cause) {
        if (setState(State.OPEN, State.CLOSED)) {
            addObjectOrEvent(new CloseEvent(cause));
            return true;
        }
        return false;
    }

    private boolean setState(State oldState, State newState) {
        assert newState != State.OPEN : "oldState: " + oldState + ", newState: " + newState;
        return stateUpdater.compareAndSet(this, oldState, newState);
    }

    private void cleanup() {
        cleanupQueue(subscription, queue);
    }
}
