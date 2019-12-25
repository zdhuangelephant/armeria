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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.Test;

import com.linecorp.armeria.client.EventLoopScheduler.Entry;

import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;

public class EventLoopSchedulerTest {

    private static final int GROUP_SIZE = 3;
    private static final EventLoopGroup group = new DefaultEventLoopGroup(GROUP_SIZE);
    private static final Endpoint endpoint = Endpoint.of("example.com");

    /**
     * A simple case.
     * (acquire, release) * 3.
     */
    @Test
    public void acquireAndRelease() {
        final EventLoopScheduler s = new EventLoopScheduler(group);
        final Entry e0 = s.acquire(endpoint);
        final EventLoop loop = e0.get();
        assertThat(e0.id()).isZero();
        assertThat(e0.activeRequests()).isEqualTo(1);
        e0.release();
        assertThat(e0.activeRequests()).isZero();

        for (int i = 0; i < 2; i++) {
            final Entry e0again = s.acquire(endpoint);
            assertThat(e0again).isSameAs(e0);
            assertThat(e0again.id()).isZero();
            assertThat(e0again.activeRequests()).isEqualTo(1);
            assertThat(e0again.get()).isSameAs(loop);
            e0again.release();
        }
    }

    /**
     * Slightly more complicated case.
     * (acquire(1), acquire(2), acquire(3), release(1), release(2), release(3))
     * 有序的release和有序的acquire
     */
    @Test
    public void orderedRelease() {
        final EventLoopScheduler s = new EventLoopScheduler(group);

        // acquire() should return the entry 0 because all entries have same activeRequests (0).
        final Entry e0 = s.acquire(endpoint);
        final EventLoop loop1 = e0.get();
        assertThat(e0.id()).isZero();
        assertThat(e0.activeRequests()).isEqualTo(1);

        // acquire() should return the entry 1 because it's the entry with the lowest ID
        // among the entries with the least activeRequests.
        final Entry e1 = s.acquire(endpoint);
        final EventLoop loop2 = e1.get();
        assertThat(e1).isNotSameAs(e0);
        assertThat(loop2).isNotSameAs(loop1);
        assertThat(e1.id()).isEqualTo(1);
        assertThat(e1.activeRequests()).isEqualTo(1); // 因为是新加入树的节点所以， 其activeRequests=1

        // acquire() should return the entry 2 because it's the entry with the lowest ID
        // among the entries with the least activeRequests.
        final Entry e2 = s.acquire(endpoint);
        final EventLoop loop3 = e2.get();
        assertThat(e2).isNotSameAs(e0);
        assertThat(e2).isNotSameAs(e1);
        assertThat(loop3).isNotSameAs(loop1);
        assertThat(loop3).isNotSameAs(loop2);
        assertThat(e2.id()).isEqualTo(2);  // 因为每次创建Entry节点的时候， 其id就是当前树的长度
        assertThat(e2.activeRequests()).isEqualTo(1);  // 同样的道理， 这个也是新加入的树的节点， 所以其activeRequests=1

        // Releasing the entry 0 will change its activeRequests back to 0,
        // and acquire() will return the entry 0 again because it's the entry
        // with the lowest ID among the entries with the least activeRequests.
        e0.release(); // 还有一点release()方法的调用，不会触发cleanUp()的调用，且只有cleanUp()会删除废弃了的entry节点。
        assertThat(e0.activeRequests()).isZero();
        final Entry e0again = s.acquire(endpoint);
        assertThat(e0again).isSameAs(e0); // 故而e0again是和e0是一个entry的断言会通过。
        assertThat(e0again.activeRequests()).isEqualTo(1);

        // Releasing the entry 1 will change its activeRequests back to 0,
        // and acquire() will return the entry 1 again because it's the entry
        // with the lowest ID among the entries with the least activeRequests.
        e1.release();
        assertThat(e1.activeRequests()).isZero();
        final Entry e1again = s.acquire(endpoint);
        assertThat(e1again).isSameAs(e1);
        assertThat(e1again.activeRequests()).isEqualTo(1);

        // Releasing the entry 2 will change its activeRequests back to 0,
        // and acquire() will return the entry 2 again because it's the entry
        // with the lowest ID among the entries with the least activeRequests.
        e2.release();
        assertThat(e2.activeRequests()).isZero();
        final Entry e2again = s.acquire(endpoint);
        assertThat(e2again).isSameAs(e2);
        assertThat(e2again.activeRequests()).isEqualTo(1);
    }

    /**
     * Similar to {@link #orderedRelease()}, but entries are released non-sequentially. 但是entries的释放是无序的。
     */
    @Test
    public void unorderedRelease() {
        final EventLoopScheduler s = new EventLoopScheduler(group);

        // acquire() should return the entry 0 because all entries have same activeRequests (0).
        // acquire() 应该返回entry0, 因为所有的entries实体有着同样的activeRequests，他们都是0
        final Entry e0 = s.acquire(endpoint);
        final EventLoop loop1 = e0.get();
        assertThat(e0.id()).isZero();
        assertThat(e0.activeRequests()).isEqualTo(1);


        // acquire() should return the entry 1 because it's the entry with the lowest ID
        // among the entries with the least activeRequests.
        // acquire() 应该返回entry1， 因为被返回的这个entry它在所有的最小活跃数群体中，它的id最小
        final Entry e1 = s.acquire(endpoint);
        final EventLoop loop2 = e1.get();
        assertThat(e1).isNotSameAs(e0);
        assertThat(loop2).isNotSameAs(loop1);
        assertThat(e1.id()).isEqualTo(1);
        assertThat(e1.activeRequests()).isEqualTo(1);

        // acquire() should return the entry 2 because it's the entry with the lowest ID
        // among the entries with the least activeRequests.
        final Entry e2 = s.acquire(endpoint);
        final EventLoop loop3 = e2.get();
        assertThat(e2).isNotSameAs(e0);
        assertThat(e2).isNotSameAs(e1);
        assertThat(loop3).isNotSameAs(loop1);
        assertThat(loop3).isNotSameAs(loop2);
        assertThat(e2.id()).isEqualTo(2);
        assertThat(e2.activeRequests()).isEqualTo(1);

        // Releasing the entry 1 will change its activeRequests back to 0,
        // and acquire() will return the entry 1 again because it's the entry
        // with the lowest ID among the entries with the least activeRequests.
        // 释放掉entry 1 将会改变它本身的活跃数重回到0的起点，所以acquire依然返回entry1。
        // 因为entry 1 是在所有最小活跃数群体中id值最小的一个。
        e1.release();
        assertThat(e1.activeRequests()).isZero();
        final Entry e1again = s.acquire(endpoint);
        assertThat(e1again).isSameAs(e1);
        assertThat(e1again.activeRequests()).isEqualTo(1);

        // Releasing the entry 2 will change its activeRequests back to 0,
        // and acquire() will return the entry 2 again because it's the entry
        // with the lowest ID among the entries with the least activeRequests.
        // 释放掉entry 2 将会改变它本身的活跃数重回到0的起点，所以acquire依然返回entry2。
        // 因为entry 2 是在所有最小活跃数群体中id值最小的一个。
        e2.release();
        assertThat(e2.activeRequests()).isZero();
        final Entry e2again = s.acquire(endpoint);
        assertThat(e2again).isSameAs(e2);
        assertThat(e2again.activeRequests()).isEqualTo(1);

        // Releasing the entry 0 will change its activeRequests back to 0,
        // and acquire() will return the entry 0 again because it's the entry
        // with the lowest ID among the entries with the least activeRequests.
        // 释放掉entry 0 将会使得它本身的活跃数重回到0的起点，所以acquire依然返回entry0。
        // 因为entry 0 是在所有最小活跃数群体中id值最小的一个。
        e0.release();
        assertThat(e0.activeRequests()).isZero();
        final Entry e0again = s.acquire(endpoint);
        assertThat(e0again).isSameAs(e0);
        assertThat(e0again.activeRequests()).isEqualTo(1);
    }

    /**
     * Makes sure different endpoints get different entries.
     * 测试不同的Endpoint可以拿到不同的entries
     */
    @Test
    public void multipleEndpoints() {
        final EventLoopScheduler s = new EventLoopScheduler(group);
        final Endpoint endpointA = Endpoint.of("a.com");
        final Endpoint endpointB = Endpoint.of("b.com");
        final Set<Entry> entriesA = new LinkedHashSet<>();
        final Set<Entry> entriesB = new LinkedHashSet<>();
        for (int i = 0; i < GROUP_SIZE; i++) {
            entriesA.add(s.acquire(endpointA));
            entriesB.add(s.acquire(endpointB));
        }
        assertThat(entriesA).hasSize(GROUP_SIZE);
        assertThat(entriesB).hasSize(GROUP_SIZE);

        // At this point, all entries should have activeRequests of 1.
        entriesA.forEach(e -> assertThat(e.activeRequests()).isEqualTo(1));
        entriesB.forEach(e -> assertThat(e.activeRequests()).isEqualTo(1));

        // Acquire again for endpoint A.
        for (int i = 0; i < GROUP_SIZE; i++) {
            entriesA.add(s.acquire(endpointA));
        }
        assertThat(entriesA).hasSize(GROUP_SIZE);
        entriesA.forEach(e -> assertThat(e.activeRequests()).isEqualTo(2));

        // The entries for endpoint B shouldn't be affected.
        entriesB.forEach(e -> assertThat(e.activeRequests()).isEqualTo(1));
    }

    @Test
    public void stressTest() {
        final EventLoopGroup group = new DefaultEventLoopGroup(1024);
        final EventLoopScheduler s = new EventLoopScheduler(group);

        final List<Entry> acquiredEntries = new ArrayList<>();
        stressTest(s, acquiredEntries, 0.8);
        stressTest(s, acquiredEntries, 0.5);
        stressTest(s, acquiredEntries, 0.2);

        // Release all acquired entries to make sure activeRequests are all 0.
        acquiredEntries.forEach(Entry::release);
        final List<Entry> entries = s.entries(endpoint);
        for (Entry e : entries) {
            assertThat(e.activeRequests()).withFailMessage("All entries must have 0 activeRequests.").isZero();
        }
        assertThat(entries.get(0).id()).isZero();
    }

    private static void stressTest(EventLoopScheduler s, List<Entry> acquiredEntries, double acquireRatio) {
        final List<Entry> entries = s.entries(endpoint);
        final Random random = ThreadLocalRandom.current();
        final int acquireRatioAsInt = (int) (Integer.MAX_VALUE * acquireRatio);

        for (int i = 0; i < 16384; i++) {
            // Strictly speaking, this can yield a negative value (Integer.MIN_VALUE),
            // but it shouldn't affect the outcome of this test.
            final int randomValue = Math.abs(random.nextInt());
            if (randomValue < acquireRatioAsInt) {
                final Entry e = s.acquire(endpoint);
                acquiredEntries.add(e);

                // The acquired entry must be the best available.
                final int activeRequests = e.activeRequests() - 1;
                for (Entry entry : entries) {
                    if (activeRequests == entry.activeRequests()) {
                        assertThat(e.id()).isLessThan(entry.id());
                    } else {
                        assertThat(activeRequests).isLessThan(entry.activeRequests());
                    }
                }
            } else if (!acquiredEntries.isEmpty()) {
                final Entry e = acquiredEntries.remove(random.nextInt(acquiredEntries.size()));
                e.release();
            }
        }
    }
}
