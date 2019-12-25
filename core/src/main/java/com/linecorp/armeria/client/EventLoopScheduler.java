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

package com.linecorp.armeria.client;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.Streams;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.util.ReleasableHolder;

import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;

/**
 * 事件调度器
 */
final class EventLoopScheduler {

    // 清扫时间间隔。
    private static final long CLEANUP_INTERVAL_NANOS = Duration.ofMinutes(1).toNanos();

    /**
     * 如果用户没有显示的设置，则使用{@link CommonPools.workerGroup()}
     */
    private final List<EventLoop> eventLoops;
    /**
     * authority-> State的映射关系维护
     */
    private final Map<String, State> map = new ConcurrentHashMap<>();
    private int counter;
    private volatile long lastCleanupTimeNanos = System.nanoTime();

    EventLoopScheduler(EventLoopGroup eventLoopGroup) {
        /**
         * eventLoopGroup实际就是一个NioEventLoop的实例。
         */
        eventLoops = Streams.stream(eventLoopGroup)
                            .map(EventLoop.class::cast)
                            .collect(toImmutableList());
    }

    Entry acquire(Endpoint endpoint) {
        requireNonNull(endpoint, "endpoint");
        final State state = state(endpoint);
        /**
         * 二叉堆树获取最为空闲的那一个连接
         */
        final Entry acquired = state.acquire();
        cleanup();
        return acquired;
    }

    // 仅仅用于单元测试
    @VisibleForTesting
    List<Entry> entries(Endpoint endpoint) {
        return state(endpoint).entries();
    }

    private State state(Endpoint endpoint) {
        final String authority = endpoint.authority();
        return map.computeIfAbsent(authority, e -> new State(eventLoops));
    }

    /**
     * Cleans up empty entries with no activity for more than 1 minute. For reduced overhead, we perform this
     * only when 1) the last clean-up was more than 1 minute ago and 2) the number of acquisitions % 256 is 0.
     *
     * <p>清除哪些已经超过一分钟其依然没有活跃连接的空entries,即清除State实例。为了降低CleanUp的开销。我们优化了两点
     * <ol>
     *     <li>距离最后一次清扫已经过去了一分钟了</li>
     *     <li>每256次进行一次清洗操作</li>
     * </ol>
     *
     * tips：
     *  Netty中提到过， {@link System.nanoTime()}是对性能损害比较大的。理应该避免调用。
     */
    private void cleanup() {
        if ((++counter & 0xFF) != 0) { // (++counter % 256) != 0
            return;
        }

        final long currentTimeNanos = System.nanoTime();
        if (currentTimeNanos - lastCleanupTimeNanos < CLEANUP_INTERVAL_NANOS) {
            return;
        }

        for (final Iterator<State> i = map.values().iterator(); i.hasNext();) {
            final State state = i.next();
            final boolean remove;

            synchronized (state) {
                remove = state.allActiveRequests == 0 &&
                         currentTimeNanos - state.lastActivityTimeNanos >= CLEANUP_INTERVAL_NANOS;
            }

            if (remove) {
                i.remove();
            }
        }

        lastCleanupTimeNanos = System.nanoTime();
    }

    /**
     * 这是一颗二叉树的抽象实体定义。即这个类本质上就是一个二叉树。
     */
    private static final class State {
        /**
         * A binary heap of Entry. Ordered by:
         * <ul>
         *   <li>{@link Entry#activeRequests()} (lower is better)</li>
         *   <li>{@link Entry#id()} (lower is better)</li>
         * </ul>
         * <p>二叉堆其节点为Entry。排序依据:
         * <ol>
         *     <li>{@link Entry#activeRequests()} 越小越好</li>
         *     <li>{@link Entry#id()} 越小越好</li>
         * </ol>
         *
         * 待排序的集合。
         */
        private final List<Entry> entries;

        private final List<EventLoop> eventLoops;
        private int nextUnusedEventLoopIdx;
        // 当前树的下的每个节点内activeRequests数量的和。
        private int allActiveRequests;

        /**
         * Updated only when {@link #allActiveRequests} is 0 by {@link #release(Entry)}.
         */
        private long lastActivityTimeNanos = System.nanoTime();

        State(List<EventLoop> eventLoops) {
            this.eventLoops = eventLoops;
            entries = new ArrayList<>();
            nextUnusedEventLoopIdx = ThreadLocalRandom.current().nextInt(eventLoops.size());
            addUnusedEventLoop();
        }

        List<Entry> entries() {
            return entries;
        }

        synchronized Entry acquire() {
            Entry e = entries.get(0);
            if (e.activeRequests() > 0) {
                // All event loops are handling connections; try to add an unused event loop.
                // 如果所有的EventLoop都已经包含已激活的链接。那么我们则尝试新压入一个崭新的eventLoop。即向二叉树添加一个新节点，如下:
                if (addUnusedEventLoop()) {
                    e = entries.get(0);
                    // 因为是压入的，所以我们这会有个断言。
                    assert e.activeRequests() == 0;
                }
            }
            // 即Entry的id为0的时候，说明是第一次调用。所以会有如下断言。
            assert e.index() == 0;
            e.activeRequests++;
            allActiveRequests++;
            bubbleDown(0);
            return e;
        }

        /**
         * 添加一个暂时不用的EventLoop，并封装成Entry的节点，添加进二叉树中。
         * @return
         */
        private boolean addUnusedEventLoop() {
            if (entries.size() < eventLoops.size()) {
                // 向此二叉树添加新的节点。从eventLoops挑一个eventLoop，并且把当前entries的length当做新节点的id。
                push(new Entry(this, eventLoops.get(nextUnusedEventLoopIdx), entries.size()));
                // 将索引自动 + 1, 即向下一位移动
                nextUnusedEventLoopIdx = (nextUnusedEventLoopIdx + 1) % eventLoops.size();
                return true;
            } else {
                return false;
            }
        }

        /**
         * release方法的调用只能在当前State下的Entry实例内发起。所以存在断言就不奇怪了。
         * @param e
         */
        synchronized void release(Entry e) {
            assert e.parent() == this;
            e.activeRequests--;
            bubbleUp(e.index());
            if (--allActiveRequests == 0) {
                lastActivityTimeNanos = System.nanoTime();
            }
        }

        // https://stackoverflow.com/a/714873 修改自这
        // PriorityQueue如果某个元素的优先级发生了更改。思考：队列的顺序是如何来动态维护的?
        // Heap implementation, modified from the public domain code at https://stackoverflow.com/a/714873
        // 堆实现
        private void push(Entry e) {
            entries.add(e);
            bubbleUp(entries.size() - 1);
        }

        private void bubbleDown(int i) {
            int best = i;
            for (;;) {
                final int oldBest = best;
                final int left = left(best);

                if (left < entries.size()) {
                    final int right = right(best);
                    if (isBetter(left, best)) {
                        if (right < entries.size()) {
                            if (isBetter(right, left)) {
                                // Left leaf is better but right leaf is even better.
                                best = right;
                            } else {
                                // Left leaf is better than the current entry and right left.
                                best = left;
                            }
                        } else {
                            // Left leaf is better and there's no right leaf.
                            best = left;
                        }
                    } else if (right < entries.size()) {
                        if (isBetter(right, best)) {
                            // Left leaf is not better but right leaf is better.
                            best = right;
                        } else {
                            // Both left and right leaves are not better.
                            break;
                        }
                    } else {
                        // Left leaf is not better and there's no right leaf.
                        break;
                    }
                } else {
                    // There are no leaves, because right leaf can't be present if left leaf isn't.
                    break;
                }

                swap(best, oldBest);
            }
        }

        private void bubbleUp(int i) {
            while (i > 0) {
                final int parent = parent(i);
                if (isBetter(parent, i)) {
                    break;
                }

                swap(parent, i);
                i = parent;
            }
        }

        /**
         * Returns {@code true} if the entry at {@code a} is a better choice than the entry at {@code b}.
         * <br/>
         * 如果索引a处的选择要比索引b处的选择更好的话。则返回true。反之，返回false
         *
         * 如何定义更好: 即索引a处对应元素的activeRequests < 索引b处对应元素的activeRequests 即a是最佳选择。
         */
        private boolean isBetter(int a, int b) {
            final Entry entryA = entries.get(a);
            final Entry entryB = entries.get(b);
            if (entryA.activeRequests() < entryB.activeRequests()) {
                return true;
            }
            if (entryA.activeRequests() > entryB.activeRequests()) {
                return false;
            }
            // 如果activeRequests数量相等的话，则比较id
            return entryA.id() < entryB.id();
        }

        /**
         * 获取父节点的索引
         * @param i
         * @return 父节点的索引
         */
        private static int parent(int i) {
            return (i - 1) / 2;
        }

        /**
         * 返回左孩子节点的索引
         * @param i
         * @return
         */
        private static int left(int i) {
            return 2 * i + 1;
        }

        /**
         * 返回右孩子节点的索引
         * @param i
         * @return
         */
        private static int right(int i) {
            return 2 * i + 2;
        }

        /**
         * 两个索引对应的元素进行位置交换。
         * @param i
         * @param j
         */
        private void swap(int i, int j) {
            final Entry entryI = entries.get(i);
            final Entry entryJ = entries.get(j);
            entries.set(i, entryJ);
            entries.set(j, entryI);

            // 不要忘记把index替换掉。嘎嘎！
            // Swap the index as well.
            entryJ.setIndex(i);
            entryI.setIndex(j);
        }

        @Override
        public String toString() {
            return '[' + Joiner.on(", ").join(entries) + ']';
        }
    }

    /**
     * 二叉树中的节点实例
     */
    static final class Entry implements ReleasableHolder<EventLoop> {
        // 当前节点的所属父State
        private final State parent;
        // 当前节点绑定的NioEventLoop。
        private final EventLoop eventLoop;
        // 当前节点id
        private final int id;
        // 当前node的活跃请求数量
        private int activeRequests;

        /**
         * Index in the binary heap {@link State#entries}. Updated by {@link State#swap(int, int)} after
         * {@link #activeRequests} is updated by {@link State#acquire()} and {@link State#release(Entry)}.
         * <br/>
         *
         * {@link State#entries}二叉堆排的索引。其会在{@link State#acquire()}和{@link State#release(Entry)}的调用，更新{@link #activeRequests}数量以后。
         * 会被{@link State#swap(int, int)}调用更新该值即index。
         */
        private int index;

        Entry(State parent, EventLoop eventLoop, int id) {
            this.parent = parent;
            this.eventLoop = eventLoop;
            this.id = index = id;
        }

        @Override
        public EventLoop get() {
            return eventLoop;
        }

        State parent() {
            return parent;
        }

        int id() {
            return id;
        }

        int index() {
            return index;
        }

        void setIndex(int index) {
            this.index = index;
        }

        int activeRequests() {
            return activeRequests;
        }

        /*
            代码读到这里，发现Entry这个类很鸡贼呀，Entry实现了ReleasableHolder<T接口。
            所以，release()方法的直接调用，将直接在UserClient内发起了。进到方法体之后才会传递到所属State内。

            哈哈，有点不按套路出门的节奏，釜底抽薪！
         */
        @Override
        public void release() {
            parent.release(this);
        }

        @Override
        public String toString() {
            return "(" + index + ", " + id + ", " + activeRequests + ')';
        }
    }
}
