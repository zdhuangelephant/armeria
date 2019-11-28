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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Ticker;

/**
 * A non-blocking implementation of circuit breaker pattern.
 * <br/>
 * circuit breaker的非阻塞实现
 */
final class NonBlockingCircuitBreaker implements CircuitBreaker {

    private static final Logger logger = LoggerFactory.getLogger(NonBlockingCircuitBreaker.class);

    /**
     * CircuitBreaker实例的生成序列号
     */
    private static final AtomicLong seqNo = new AtomicLong(0);

    /**
     * CircuitBreaker实例的命名
     */
    private final String name;

    private final CircuitBreakerConfig config;

    private final AtomicReference<State> state;

    private final Ticker ticker;

    /**
     * Creates a new {@link NonBlockingCircuitBreaker} with the specified {@link Ticker} and
     * {@link CircuitBreakerConfig}.
     */
    NonBlockingCircuitBreaker(Ticker ticker, CircuitBreakerConfig config) {
        this.ticker = requireNonNull(ticker, "ticker");
        this.config = requireNonNull(config, "config");
        // 如果name为空，则取默认命名方式
        name = config.name().orElseGet(() -> "circuit-breaker-" + seqNo.getAndIncrement());
        state = new AtomicReference<>(newClosedState());
        logStateTransition(CircuitState.CLOSED, null);
        notifyStateChanged(CircuitState.CLOSED);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void onSuccess() {
        final State currentState = state.get();
        // 如果是Close状态， 则获取currentState持有的EventCount引用，并做onSuccess回调。
        if (currentState.isClosed()) {
            // fires success event
            final Optional<EventCount> updatedCount = currentState.counter().onSuccess();
            // notifies the count if it has been updated
            // NOTE： 只有State引用被实际替换了，updatedCount内才持有新的EventCount引用。有了实际的引用，才可以通知监听器们
            updatedCount.ifPresent(this::notifyCountUpdated);
        } else if (currentState.isHalfOpen()) {
            // changes to CLOSED if at least one request succeeds during HALF_OPEN
            // 如果是half-open状态下，并且至少一个request是成功的，那么就会将currentState置为Close状态。并通知监听器们
            if (state.compareAndSet(currentState, newClosedState())) {
                logStateTransition(CircuitState.CLOSED, null);
                notifyStateChanged(CircuitState.CLOSED);
            }
        }
    }

    @Override
    public void onFailure() {
        final State currentState = state.get();
        // 如果是Closed则 -> Open状态
        if (currentState.isClosed()) {
            // fires failure event
            final Optional<EventCount> updatedCount = currentState.counter().onFailure();
            // checks the count if it has been updated
            // 检查EventCount是否被更新
            updatedCount.ifPresent(count -> {
                // changes to OPEN if failure rate exceeds the threshold
                // 如果失败率超过了阈值，则将状态置为Open
                if (checkIfExceedingFailureThreshold(count) &&
                    state.compareAndSet(currentState, newOpenState())) {
                    logStateTransition(CircuitState.OPEN, count);
                    notifyStateChanged(CircuitState.OPEN);
                } else {
                    notifyCountUpdated(count);
                }
            });
        } else if (currentState.isHalfOpen()) {
            // returns to OPEN if a request fails during HALF_OPEN
            // 如果是half-open -> Open状态
            if (state.compareAndSet(currentState, newOpenState())) {
                logStateTransition(CircuitState.OPEN, null);
                notifyStateChanged(CircuitState.OPEN);
            }
        }
    }

    /**
     * 检查是否超过失败次数的阈值
     * @param count
     * @return
     */
    private boolean checkIfExceedingFailureThreshold(EventCount count) {
        return 0 < count.total() &&
               config.minimumRequestThreshold() <= count.total() &&
               config.failureRateThreshold() < count.failureRate();
    }

    @Override
    public boolean canRequest() {
        final State currentState = state.get();
        if (currentState.isClosed()) {
            // all requests are allowed during CLOSED
            return true;
        }
        // 如果状态时Half-Open或Open，则需要检查Open状态下的timedOutTimeNanos是否超时，如果超时，则通过cas将其替换为Half-Open状态，此时是允许请求远端服务；
        // 如果Open状态下的timedOutTimeNanos没有超时，则直接fast-fail即快速失败。
        if (currentState.isHalfOpen() || currentState.isOpen()) {
            if (currentState.checkTimeout() && state.compareAndSet(currentState, newHalfOpenState())) {
                // changes to HALF_OPEN if OPEN state has timed out
                logStateTransition(CircuitState.HALF_OPEN, null);
                notifyStateChanged(CircuitState.HALF_OPEN);
                return true;
            }
            // all other requests are refused
            notifyRequestRejected();
            return false;
        }

        return true;
    }

    /**
     * 创建一个Open状态的实例
     * @return
     */
    private State newOpenState() {
        return new State(CircuitState.OPEN, config.circuitOpenWindow(), NoOpCounter.INSTANCE);
    }

    /**
     * 创建一个Half-Open状态的实例
     * @return
     */
    private State newHalfOpenState() {
        return new State(CircuitState.HALF_OPEN, config.trialRequestInterval(), NoOpCounter.INSTANCE);
    }

    /**
     * 创建一个Closed状态的实例
     * @return
     */
    private State newClosedState() {
        return new State(
                CircuitState.CLOSED,
                Duration.ZERO,
                new SlidingWindowCounter(ticker, config.counterSlidingWindow(),
                                         config.counterUpdateInterval()));
    }

    /**
     * 简单的日志记录，无实际逻辑操作
     * @param circuitState
     * @param count
     */
    private void logStateTransition(CircuitState circuitState, @Nullable EventCount count) {
        if (logger.isInfoEnabled()) {
            final int capacity = name.length() + circuitState.name().length() + 32;
            final StringBuilder builder = new StringBuilder(capacity);
            builder.append("name:");
            builder.append(name);
            builder.append(" state:");
            builder.append(circuitState.name());
            if (count != null) {
                builder.append(" fail:");
                builder.append(count.failure());
                builder.append(" total:");
                builder.append(count.total());
            }
            logger.info(builder.toString());
        }
    }

    /**
     * 因CircuitState内部状态的变更，触发所有的监听器
     * @param circuitState
     */
    private void notifyStateChanged(CircuitState circuitState) {
        config.listeners().forEach(listener -> {
            try {
                listener.onStateChanged(name(), circuitState);
            } catch (Throwable t) {
                logger.warn("An error occurred when notifying a StateChanged event", t);
            }
            notifyCountUpdated(listener, EventCount.ZERO);
        });
    }

    private void notifyCountUpdated(EventCount count) {
        config.listeners().forEach(listener -> notifyCountUpdated(listener, count));
    }

    /**
     * 当EventCount被更新的时候会触发该方法
     * @param listener
     * @param count
     */
    private void notifyCountUpdated(CircuitBreakerListener listener, EventCount count) {
        try {
            listener.onEventCountUpdated(name(), count);
        } catch (Throwable t) {
            logger.warn("An error occurred when notifying an EventCountUpdated event", t);
        }
    }

    /**
     * 当请求被fast-fail的时候，会被触发该方法
     */
    private void notifyRequestRejected() {
        config.listeners().forEach(listener -> {
            try {
                listener.onRequestRejected(name());
            } catch (Throwable t) {
                logger.warn("An error occurred when notifying a RequestRejected event", t);
            }
        });
    }

    @VisibleForTesting
    State state() {
        return state.get();
    }

    @VisibleForTesting
    CircuitBreakerConfig config() {
        return config;
    }

    /**
     * The internal state of the circuit breaker.
     * <br/>
     * CircuitBreaker实例的内部状态维护类
     */
    final class State {
        // 当前状态
        private final CircuitState circuitState;
        // 计数器
        private final EventCounter counter;
        // 超时时间
        private final long timedOutTimeNanos;

        /**
         * Creates a new instance.
         *
         * @param circuitState The circuit state
         * @param timeoutDuration The max duration of the state
         * @param counter The event counter to use during the state
         */
        private State(CircuitState circuitState, Duration timeoutDuration, EventCounter counter) {
            this.circuitState = circuitState;
            this.counter = counter;

            if (timeoutDuration.isZero() || timeoutDuration.isNegative()) {
                timedOutTimeNanos = 0L;
            } else {
                timedOutTimeNanos = ticker.read() + timeoutDuration.toNanos();
            }
        }

        private EventCounter counter() {
            return counter;
        }

        /**
         * Returns {@code true} if this state has timed out.
         * 检查该state的实例是否超时
         */
        private boolean checkTimeout() {
            return 0 < timedOutTimeNanos && timedOutTimeNanos <= ticker.read();
        }

        boolean isOpen() {
            return circuitState == CircuitState.OPEN;
        }

        boolean isHalfOpen() {
            return circuitState == CircuitState.HALF_OPEN;
        }

        boolean isClosed() {
            return circuitState == CircuitState.CLOSED;
        }
    }

    /**
     * 没有任何操作的EventCounter
     */
    private static class NoOpCounter implements EventCounter {

        private static final NoOpCounter INSTANCE = new NoOpCounter();

        @Override
        public EventCount count() {
            return EventCount.ZERO;
        }

        @Override
        public Optional<EventCount> onSuccess() {
            return Optional.empty();
        }

        @Override
        public Optional<EventCount> onFailure() {
            return Optional.empty();
        }
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("name", name)
                          .add("config", config)
                          .toString();
    }
}
