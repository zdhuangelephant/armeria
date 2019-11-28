/*
 * Copyright 2018 LINE Corporation
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

import static com.spotify.futures.CompletableFutures.exceptionallyCompletedFuture;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.completedFuture;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides asynchronous start-stop life cycle support.  提供了异步的启动/停止生命周期的支持
 *
 * @param <T> the type of the startup argument. Use {@link Void} if unused.   启动参数
 * @param <U> the type of the shutdown argument. Use {@link Void} if unused.  关机参数
 * @param <V> the type of the startup result. Use {@link Void} if unused.    启动结果
 * @param <L> the type of the life cycle event listener. Use {@link Void} if unused.  生命周期监听者
 */
public abstract class StartStopSupport<T, U, V, L> implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(StartStopSupport.class);

    enum State {
        // 启动中
        STARTING,
        // 已启动
        STARTED,
        // 关机中
        STOPPING,
        // 已关机
        STOPPED
    }
    // 这个一个线程池，里面只有一根线程
    private final Executor executor;
    // 监听者们
    private final List<L> listeners = new CopyOnWriteArrayList<>();
    private volatile State state = State.STOPPED;
    /**
     * This future is {@code V}-typed when STARTING/STARTED and {@link Void}-typed when STOPPING/STOPPED.
     * <br/>
     * Server的启动或停止，的future共享对象声明。
     */
    private CompletableFuture<?> future = completedFuture(null);

    /**
     * Creates a new instance.
     *
     * @param executor the {@link Executor} which will be used for invoking the extension points of this class:
     *                 <ul>
     *                   <li>{@link #doStart(Object)}</li>
     *                   <li>{@link #doStop(Object)}</li>
     *                   <li>{@link #rollbackFailed(Throwable)}</li>
     *                   <li>{@link #notificationFailed(Object, Throwable)}</li>
     *                   <li>All listener notifications</li>
     *                 </ul>
     *                 .. except {@link #closeFailed(Throwable)} which is invoked at the caller thread.  {@link #closeFailed(Throwable)}这个方法会在调用线程内完成。不需要{@link Executor}的帮忙
     */
    protected StartStopSupport(Executor executor) {
        this.executor = requireNonNull(executor, "executor");
    }

    /**
     * Adds the specified {@code listener}, so that it is notified when the state of this
     * {@link StartStopSupport} changes.
     * <br/>
     * 添加一个监听者， 当状态变更的时候，所有的监听者会被通知到。
     */
    public final void addListener(L listener) {
        listeners.add(requireNonNull(listener, "listener"));
    }

    /**
     * Removes the specified {@code listener}, so that it is not notified anymore.
     * <br/>
     * 删除一个监听者， 删除以后，它将不会再被通知。
     */
    public final boolean removeListener(L listener) {
        return listeners.remove(requireNonNull(listener, "listener"));
    }

    /**
     * Begins the startup procedure without an argument by calling {@link #doStart(Object)}, ensuring that
     * neither {@link #doStart(Object)} nor {@link #doStop(Object)} is invoked concurrently. When the startup
     * fails, {@link #stop()} will be invoked automatically to roll back the side effect caused by this method
     * and any exceptions that occurred during the rollback will be reported to
     * {@link #rollbackFailed(Throwable)}. This method is a shortcut of
     * {@code start(null, null, failIfStarted)}.
     *
     * <br/>
     * 不携带任何参数的通过调用{@link #doStart(Object)}来开启一个启动着。确信{@link #doStart(Object)}和{@link #doStop(Object)}不会并发的调用。
     * 当启动失败后， 系统自动会调用stop方法，并且回滚被该方法造成地一切"边际效果"，与此同时，回滚期间的所有的异常信息都会上报给{@link #rollbackFailed(Throwable)}。这个方法是{@code start(null, null, failIfStarted)}的一个缩影。
     *
     * @param failIfStarted whether to fail the returned {@link CompletableFuture} with
     *                      an {@link IllegalStateException} when the startup procedure is already
     *                      in progress or done   对于已经开始或处理完毕的状态，是否抛出异常
     */
    public final CompletableFuture<V> start(boolean failIfStarted) {
        return start(null, null, failIfStarted);
    }

    /**
     * Begins the startup procedure without an argument by calling {@link #doStart(Object)}, ensuring that
     * neither {@link #doStart(Object)} nor {@link #doStop(Object)} is invoked concurrently. When the startup
     * fails, {@link #stop()} will be invoked automatically to roll back the side effect caused by this method
     * and any exceptions that occurred during the rollback will be reported to
     * {@link #rollbackFailed(Throwable)}. This method is a shortcut of
     * {@code start(arg, null, failIfStarted)}.
     *
     * <br/>
     * 不携带任何参数的通过调用{@link #doStart(Object)}来开启一个启动着。确信{@link #doStart(Object)}和{@link #doStop(Object)}不会并发的调用。
     * 当启动失败后， 系统自动会调用stop方法，并且回滚被该方法造成地一切"边际效果"，与此同时，回滚期间的所有的异常信息都会上报给{@link #rollbackFailed(Throwable)}。这个方法是{@code start(null, null, failIfStarted)}的一个缩影。
     *
     * @param arg           the argument to pass to {@link #doStart(Object)},   传递给{@link #doStart(Object)}的参数，如果没有参数则为null
     *                      or {@code null} to pass no argument.
     * @param failIfStarted whether to fail the returned {@link CompletableFuture} with
     *                      an {@link IllegalStateException} when the startup procedure is already
     *                      in progress or done     对于已经开始或处理完毕的状态，是否抛出异常
     */
    public final CompletableFuture<V> start(@Nullable T arg, boolean failIfStarted) {
        return start(arg, null, failIfStarted);
    }

    /**
     * Begins the startup procedure by calling {@link #doStart(Object)}, ensuring that neither
     * {@link #doStart(Object)} nor {@link #doStop(Object)} is invoked concurrently. When the startup fails,
     * {@link #stop(Object)} will be invoked with the specified {@code rollbackArg} automatically to roll back
     * the side effect caused by this method and any exceptions that occurred during the rollback will be
     * reported to {@link #rollbackFailed(Throwable)}.
     *
     * @param arg           the argument to pass to {@link #doStart(Object)},
     *                      or {@code null} to pass no argument.        传递给{@link #doStart(Object)}的参数，如果没有参数则为null
     * @param rollbackArg   the argument to pass to {@link #doStop(Object)} when rolling back.  在回滚的时候，传递给{@link #doStop(Object)}的参数
     * @param failIfStarted whether to fail the returned {@link CompletableFuture} with
     *                      an {@link IllegalStateException} when the startup procedure is already
     *                      in progress or done     对于已经开始或处理完毕的状态，是否抛出异常
     */
    public final synchronized CompletableFuture<V> start(@Nullable T arg, @Nullable U rollbackArg,
                                                         boolean failIfStarted) {
        switch (state) {
            case STARTING:
            case STARTED:
                if (failIfStarted) {
                    return exceptionallyCompletedFuture(
                            new IllegalStateException("must be stopped to start; currently " + state));
                } else {
                    @SuppressWarnings("unchecked")
                    final CompletableFuture<V> castFuture = (CompletableFuture<V>) future;
                    return castFuture;
                }
            case STOPPING:
                // A user called start() to restart, but not stopped completely yet.
                // Try again once stopped.
                // 在还没有完全停止的时候，这个时候调用了start()来重启。如果是此类情况，则抛出异常，并且异步再次重启。
                return future.exceptionally(unused -> null)
                             .thenComposeAsync(unused -> start(arg, failIfStarted), executor);
        }

        assert state == State.STOPPED : "state: " + state;
        state = State.STARTING; // 代码执行到这里以后，则重置state的状态为State.STARTING

        // Attempt to start.  进行正式的启动操作。
        final CompletableFuture<V> startFuture = new CompletableFuture<>();
        boolean submitted = false;
        try {
            executor.execute(() -> {
                try {
                    // 唤醒所有对Server#STARTING事件感兴趣的监听者
                    notifyListeners(State.STARTING, arg, null, null);
                    /**
                     * TODO doStart(arg) 待分析 2019-11-22 18:39:33
                     */
                    final CompletionStage<V> f = doStart(arg);
                    if (f == null) {
                        throw new IllegalStateException("doStart() returned null.");
                    }

                    f.handle((result, cause) -> {
                        // 异步设置回调，对startFuture进行正确合理的设置
                        if (cause != null) {
                            startFuture.completeExceptionally(cause);
                        } else {
                            startFuture.complete(result);
                        }
                        return null;
                    });
                } catch (Exception e) {
                    startFuture.completeExceptionally(e);
                }
            });
            submitted = true;
        } catch (Exception e) {
            return exceptionallyCompletedFuture(e);
        } finally {
            // submitted==false,说明try块内的代码执行，报异常了。如果报异常，则需要重置state的状态为State.STOPPED
            if (!submitted) {
                state = State.STOPPED;
            }
        }

        // startFuture需要异步处理
        final CompletableFuture<V> future = startFuture.handleAsync((result, cause) -> {
            if (cause != null) {
                // Failed to start. Stop and complete with the start failure cause.
                // 只要cause不为null，则说明启动失败，则直接调用stop方法，并且将cause返回给客户端，告知失败原因
                /**
                 * TODO stop(rollbackArg, true) 待分析 2019-11-22 18:39:33
                 */
                final CompletableFuture<Void> rollbackFuture =
                        stop(rollbackArg, true).exceptionally(stopCause -> {
                            rollbackFailed(Exceptions.peel(stopCause));
                            return null;
                        });

                return rollbackFuture.<V>thenCompose(unused -> exceptionallyCompletedFuture(cause));
            } else {
                // cause为null，说明启动成功
                enter(State.STARTED, arg, null, result);
                return completedFuture(result);
            }
        }, executor).thenCompose(Function.identity());
        // 这个future的定义可能是个成功的future， 也可能是个失败的future
        this.future = future;
        return future;
    }

    /**
     * Begins the shutdown procedure without an argument by calling {@link #doStop(Object)}, ensuring that
     * neither {@link #doStart(Object)} nor {@link #doStop(Object)} is invoked concurrently. This method is
     * a shortcut of {@code stop(null)}.
     */
    public final CompletableFuture<Void> stop() {
        return stop(null);
    }

    /**
     * Begins the shutdown procedure by calling {@link #doStop(Object)}, ensuring that neither
     * {@link #doStart(Object)} nor {@link #doStop(Object)} is invoked concurrently.
     *
     * @param arg the argument to pass to {@link #doStop(Object)}, or {@code null} to pass no argument.
     */
    public final CompletableFuture<Void> stop(@Nullable U arg) {
        return stop(arg, false);
    }

    private synchronized CompletableFuture<Void> stop(@Nullable U arg, boolean rollback) {
        switch (state) {
            case STARTING:
                if (!rollback) {
                    // Try again once started.
                    return future.exceptionally(unused -> null) // Ignore the exception.
                                 .thenComposeAsync(unused -> stop(arg), executor);
                } else {
                    break;
                }
            case STOPPING:
            case STOPPED:
                @SuppressWarnings("unchecked")
                final CompletableFuture<Void> castFuture = (CompletableFuture<Void>) future;
                return castFuture;
        }

        assert state == State.STARTED || rollback : "state: " + state + ", rollback: " + rollback;
        final State oldState = state;
        state = State.STOPPING;

        final CompletableFuture<Void> stopFuture = new CompletableFuture<>();
        boolean submitted = false;
        try {
            executor.execute(() -> {
                try {
                    notifyListeners(State.STOPPING, null, arg, null);
                    final CompletionStage<Void> f = doStop(arg);
                    if (f == null) {
                        throw new IllegalStateException("doStop() returned null.");
                    }

                    f.handle((unused, cause) -> {
                        if (cause != null) {
                            stopFuture.completeExceptionally(cause);
                        } else {
                            stopFuture.complete(null);
                        }
                        return null;
                    });
                } catch (Exception e) {
                    stopFuture.completeExceptionally(e);
                }
            });
            submitted = true;
        } catch (Exception e) {
            return exceptionallyCompletedFuture(e);
        } finally {
            if (!submitted) {
                state = oldState;
            }
        }

        final CompletableFuture<Void> future = stopFuture.whenCompleteAsync(
                (unused1, cause) -> enter(State.STOPPED, null, arg, null), executor);
        this.future = future;
        return future;
    }

    /**
     * A synchronous version of {@link #stop(Object)}. Exceptions occurred during shutdown are reported to
     * {@link #closeFailed(Throwable)}. No argument (i.e. {@code null}) is passed.
     */
    @Override
    public final void close() {
        final CompletableFuture<Void> f;
        synchronized (this) {
            if (state == State.STOPPED) {
                return;
            }
            f = stop(null);
        }

        boolean interrupted = false;
        for (;;) {
            try {
                f.get();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            } catch (ExecutionException e) {
                closeFailed(Exceptions.peel(e));
                break;
            }
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void enter(State state, @Nullable T startArg, @Nullable U stopArg, @Nullable V startResult) {
        synchronized (this) {
            assert this.state != state : "transition to the same state: " + state;
            this.state = state;
        }
        notifyListeners(state, startArg, stopArg, startResult);
    }

    private void notifyListeners(State state, @Nullable T startArg, @Nullable U stopArg,
                                 @Nullable V startResult) {
        for (L l : listeners) {
            try {
                switch (state) {
                    case STARTING:
                        notifyStarting(l, startArg);
                        break;
                    case STARTED:
                        notifyStarted(l, startArg, startResult);
                        break;
                    case STOPPING:
                        notifyStopping(l, stopArg);
                        break;
                    case STOPPED:
                        notifyStopped(l, stopArg);
                        break;
                    default:
                        throw new Error("unknown state: " + state);
                }
            } catch (Exception cause) {
                notificationFailed(l, cause);
            }
        }
    }

    /**
     * Invoked by {@link #start(Object, boolean)} to perform the actual startup.
     *
     * <br/>
     * 该方法会被{@link #start(Object, boolean)}调用，来执行真正的启动动作
     *
     * @param arg the argument passed from {@link #start(Object, boolean)},
     *            or {@code null} if no argument was specified.
     */
    protected abstract CompletionStage<V> doStart(@Nullable T arg) throws Exception;

    /**
     * Invoked by {@link #stop(Object)} to perform the actual startup, or indirectly by
     * {@link #start(Object, boolean)} when startup failed.
     * <br/>
     * 被{@link #stop(Object)}调用，或者在启动失败的时候被间接的调用。
     *
     * @param arg the argument passed from {@link #stop(Object)},
     *            or {@code null} if no argument was specified.
     */
    protected abstract CompletionStage<Void> doStop(@Nullable U arg) throws Exception;

    /**
     * Invoked when the startup procedure begins.
     * <br/>
     * 当Server启动线程开始的时候
     *
     * @param listener the listener
     * @param arg      the argument passed from {@link #start(Object, boolean)},
     *                 or {@code null} if no argument was specified.
     */
    protected void notifyStarting(L listener, @Nullable T arg) throws Exception {}

    /**
     * Invoked when the startup procedure is finished.
     * <br/>
     * 当Server启动线程开始完毕后
     *
     * @param listener the listener
     * @param arg      the argument passed from {@link #start(Object, boolean)},
     *                 or {@code null} if no argument was specified.
     * @param result   the value of the {@link CompletionStage} returned by {@link #doStart(Object)}.
     */
    protected void notifyStarted(L listener, @Nullable T arg, @Nullable V result) throws Exception {}

    /**
     * Invoked when the shutdown procedure begins.
     * <br/>
     * 当Server在停止的时候
     *
     * @param listener the listener
     * @param arg      the argument passed from {@link #stop(Object)},
     *                 or {@code null} if no argument was specified.
     */
    protected void notifyStopping(L listener, @Nullable U arg) throws Exception {}

    /**
     * Invoked when the shutdown procedure is finished.
     * <br/>
     * 当Server在停止完毕的时候
     *
     * @param listener the listener
     * @param arg      the argument passed from {@link #stop(Object)},
     *                 or {@code null} if no argument was specified.
     */
    protected void notifyStopped(L listener, @Nullable U arg) throws Exception {}

    /**
     * Invoked when failed to stop during the rollback after startup failure.
     * <br/>
     * 在启动失败的回滚的时候，且停止不了的时候会调用该方法。
     */
    protected void rollbackFailed(Throwable cause) {
        logStopFailure(cause);
    }

    /**
     * Invoked when an event listener raises an exception.
     * <br/>
     * 当监听者抛出异常的时候会调用该方法
     */
    protected void notificationFailed(L listener, Throwable cause) {
        logger.warn("Failed to notify a listener: {}", listener, cause);
    }

    /**
     * Invoked when failed to stop in {@link #close()}.
     * <br/>
     * 在调用{@link #close()}失败的时候会调用该方法
     */
    protected void closeFailed(Throwable cause) {
        logStopFailure(cause);
    }

    private static void logStopFailure(Throwable cause) {
        logger.warn("Failed to stop: {}", cause.getMessage(), cause);
    }

    @Override
    public String toString() {
        return state.name();
    }
}
