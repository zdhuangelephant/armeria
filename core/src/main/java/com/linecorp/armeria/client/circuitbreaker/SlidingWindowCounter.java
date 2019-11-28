/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client.circuitbreaker;

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.Iterator;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

import com.google.common.base.Ticker;

/**
 * An {@link EventCounter} that accumulates the count of events within a time window.
 * <br/>
 * 在一个时间窗口内，累计增加events的次数
 */
final class SlidingWindowCounter implements EventCounter {

    private final Ticker ticker;

    /**
     * 窗口宽度(ns)
     */
    private final long slidingWindowNanos;

    /**
     * 窗口更新间隔（ns）
     */
    private final long updateIntervalNanos;

    /**
     * The reference to the latest {@link Bucket}.
     * <br/>
     * 最新的{@link Bucket}
     */
    private final AtomicReference<Bucket> current;

    /**
     * The reference to the latest accumulated {@link EventCount}.
     * <br/>
     * 返回最新的{@link EventCount}，随着时间窗口的滑动，这个引用会不断的被更新
     */
    private final AtomicReference<EventCount> snapshot = new AtomicReference<>(EventCount.ZERO);

    /**
     * The queue that stores {@link Bucket}s within the time window.
     * <br/>
     * 在一个窗口宽度时间内，来存储{@link Bucket}s 的蓄水池(即是个队列)
     */
    private final Queue<Bucket> reservoir = new ConcurrentLinkedQueue<>();

    SlidingWindowCounter(Ticker ticker, Duration slidingWindow, Duration updateInterval) {
        this.ticker = requireNonNull(ticker, "ticker");
        slidingWindowNanos = requireNonNull(slidingWindow, "slidingWindow").toNanos();
        updateIntervalNanos = requireNonNull(updateInterval, "updateInterval").toNanos();
        current = new AtomicReference<>(new Bucket(ticker.read()));
    }

    @Override
    public EventCount count() {
        return snapshot.get();
    }

    @Override
    public Optional<EventCount> onSuccess() {
        return onEvent(Event.SUCCESS);
    }

    @Override
    public Optional<EventCount> onFailure() {
        return onEvent(Event.FAILURE);
    }

    private Optional<EventCount> onEvent(Event event) {
        // 耗费的时间
        final long tickerNanos = ticker.read();
        // 当前时间
        final Bucket currentBucket = current.get();

        if (tickerNanos < currentBucket.timestamp()) {
            // if current timestamp is older than bucket's timestamp (maybe race or GC pause?),
            // then creates an instant bucket and puts it to the reservoir not to lose event.
            final Bucket bucket = new Bucket(tickerNanos);
            event.increment(bucket);
            reservoir.offer(bucket);
            return Optional.empty();
        }

        if (tickerNanos < currentBucket.timestamp() + updateIntervalNanos) {
            // increments the current bucket since it is exactly latest
            event.increment(currentBucket);
            return Optional.empty();
        }

        // the current bucket is old
        // it's time to create new one
        // currentBucket是老的了， 这时候用tickerNanos应该创建一个新的Bucket实例
        final Bucket nextBucket = new Bucket(tickerNanos);
        event.increment(nextBucket);

        // replaces the bucket
        // 将老的用新实例nextBucket替换掉， 即执行更新操作
        if (current.compareAndSet(currentBucket, nextBucket)) {
            // puts old one to the reservoir
            // 将老的bucket放入队列
            reservoir.offer(currentBucket);
            // and then updates count
            // 更新EventCount
            final EventCount eventCount = trimAndSum(tickerNanos);
            snapshot.set(eventCount);
            return Optional.of(eventCount);
        } else {
            // the bucket has been replaced already
            // puts new one as an instant bucket to the reservoir not to lose event
            // bucket已经被替换完毕后， 将nextBucket新的实例放入队列内
            reservoir.offer(nextBucket);
            return Optional.empty();
        }
    }

    /**
     * Sums up buckets within the time window, and removes all the others.
     * <br/>
     * 在当前的窗口内，统计成功次数以及失败的次数， 并且将所属上个窗口的元素都remove掉
     */
    private EventCount trimAndSum(long tickerNanos) {
        final long oldLimit = tickerNanos - slidingWindowNanos;
        final Iterator<Bucket> iterator = reservoir.iterator();
        long success = 0;
        long failure = 0;
        while (iterator.hasNext()) {
            final Bucket bucket = iterator.next();
            // 已经不属于当前窗口内的元素，都要被remove
            if (bucket.timestamp < oldLimit) {
                // removes old bucket
                iterator.remove();
            } else {
                // 在本次窗口内，统计成功次数和失败的次数
                success += bucket.success();
                failure += bucket.failure();
            }
        }

        return new EventCount(success, failure);
    }


    private enum Event {
        SUCCESS {
            @Override
            void increment(Bucket bucket) {
                bucket.success.increment();
            }
        },
        FAILURE {
            @Override
            void increment(Bucket bucket) {
                bucket.failure.increment();
            }
        };

        abstract void increment(Bucket bucket);
    }

    /**
     * Holds the count of events within {@code updateInterval}.
     * <br/>
     * 在一个合法间隔时间内，所持有的events的个数
     */
    private static final class Bucket {

        private final long timestamp;

        private final LongAdder success = new LongAdder();

        private final LongAdder failure = new LongAdder();

        private Bucket(long timestamp) {
            this.timestamp = timestamp;
        }

        private long timestamp() {
            return timestamp;
        }

        private long success() {
            return success.sum();
        }

        private long failure() {
            return failure.sum();
        }

        @Override
        public String toString() {
            return "Bucket{" +
                   "timestamp=" + timestamp +
                   ", success=" + success +
                   ", failure=" + failure +
                   '}';
        }
    }
}
