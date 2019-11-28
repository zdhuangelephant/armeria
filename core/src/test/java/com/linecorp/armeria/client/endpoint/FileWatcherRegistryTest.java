/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.client.endpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.*;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import ch.qos.logback.core.util.FileUtil;
import io.micrometer.core.instrument.util.IOUtils;
import org.awaitility.Awaitility;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.linecorp.armeria.client.endpoint.FileWatcherRegistry.FileWatchRegisterKey;

public class FileWatcherRegistryTest {

    @BeforeClass
    public static void before() {
        Awaitility.setDefaultTimeout(1, TimeUnit.MINUTES);
    }

    @AfterClass
    public static void after() {
        Awaitility.setDefaultTimeout(10, TimeUnit.SECONDS);
    }

    // Not sure if there is a better way to test multi-filesystem other than mocking..
    private static Path createMockedPath() throws Exception {
        final Path path = mock(Path.class);
        final FileSystem fileSystem = mock(FileSystem.class);
        final WatchService watchService = mock(WatchService.class);
        final WatchKey watchKey = mock(WatchKey.class);
        when(path.toRealPath()).thenReturn(path);
        when(path.getParent()).thenReturn(path);
        when(path.getFileSystem()).thenReturn(fileSystem);
        when(fileSystem.newWatchService()).thenReturn(watchService);
        when(path.register(any(), any())).thenReturn(watchKey);
        when(watchService.take()).then(invocation -> {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            return watchKey;
        });
        when(watchKey.reset()).thenReturn(true);
        return path;
    }

    @After
    public void tearDown() throws Exception {
        PropertiesEndpointGroup.resetRegistry();
    }

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void emptyGroupStopsBackgroundThread() throws Exception {

        final File file = folder.newFile("temp-file.properties");
        final File file2 = folder.newFile("temp-file2.properties");

        final FileWatcherRegistry fileWatcherRegistry =
                new FileWatcherRegistry();
        final FileWatchRegisterKey key1 = fileWatcherRegistry.register(file.toPath(), () -> {});
        final FileWatchRegisterKey key2 = fileWatcherRegistry.register(file2.toPath(), () -> {});

        assertThat(fileWatcherRegistry.isRunning()).isTrue();

        fileWatcherRegistry.unregister(key1);

        assertThat(fileWatcherRegistry.isRunning()).isTrue();

        fileWatcherRegistry.unregister(key2);

        assertThat(fileWatcherRegistry.isRunning()).isFalse();
    }

    @Test
    public void closeEndpointGroupStopsRegistry() throws Exception {

        final File file = folder.newFile("temp-file.properties");

        final FileWatcherRegistry fileWatcherRegistry = new FileWatcherRegistry();
        fileWatcherRegistry.register(file.toPath(), () -> {
            System.out.println("哈哈哈哈哈哈");
        });

        assertThat(fileWatcherRegistry.isRunning()).isTrue();

        fileWatcherRegistry.close();

        assertThat(fileWatcherRegistry.isRunning()).isFalse();
    }

    /**
     * 动态的感知目标文件的变化，并且在发生变化的同时，能够触发回调函数
     * @throws Exception
     */
    @Test
    public void closeEndpointGroupStopsRegistry1() throws Exception {
        AtomicInteger counter = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(2);

        final File file = new File("/Users/zdhuang/dev_resources/logs/temp-file.properties");

        final FileWatcherRegistry fileWatcherRegistry = new FileWatcherRegistry();
        fileWatcherRegistry.register(file.toPath(), () -> {
            System.out.println("哈哈哈哈哈哈");
            counter.incrementAndGet();
            latch.countDown();
        });

        assertThat(fileWatcherRegistry.isRunning()).isTrue();

//        fileWatcherRegistry.close();
//        assertThat(fileWatcherRegistry.isRunning()).isFalse();

        TimeUnit.SECONDS.sleep(3);

        FileOutputStream outputStream = new FileOutputStream(file);
        outputStream.write("hello".getBytes());
        outputStream.flush();
        if (null != outputStream) outputStream.close();


        TimeUnit.SECONDS.sleep(10);

        FileOutputStream outputStream1 = new FileOutputStream(file);
        outputStream1.write("world".getBytes());
        outputStream1.flush();
        if (null != outputStream1) outputStream1.close();

        TimeUnit.SECONDS.sleep(3);

        latch.await(15, TimeUnit.SECONDS);
        System.out.println("counter : " + counter.get());
    }



    @Test
    public void runnableWithExceptionContinuesRun() throws Exception {

        final File file = folder.newFile("temp-file.properties");
        final FileWatcherRegistry fileWatcherRegistry = new FileWatcherRegistry();

        final AtomicInteger val = new AtomicInteger(0);
        final FileWatchRegisterKey key = fileWatcherRegistry.register(file.toPath(), () -> {
            try {
                final BufferedReader bufferedReader = new BufferedReader(new FileReader(file));
                val.set(Integer.valueOf(bufferedReader.readLine()));
            } catch (IOException e) {
                // do nothing
            }
            throw new RuntimeException();
        });

        PrintWriter printWriter = new PrintWriter(file);
        printWriter.print(1);
        printWriter.close();

        await().untilAsserted(() -> assertThat(val.get()).isEqualTo(1));

        assertThat(fileWatcherRegistry.isRunning()).isTrue();

        printWriter = new PrintWriter(file);
        printWriter.print(2);
        printWriter.close();

        await().untilAsserted(() -> assertThat(val.get()).isEqualTo(2));

        assertThat(fileWatcherRegistry.isRunning()).isTrue();

        fileWatcherRegistry.unregister(key);

        assertThat(fileWatcherRegistry.isRunning()).isFalse();

        fileWatcherRegistry.close();
    }

    @Test
    public void testMultipleFileSystems() throws Exception {

        final FileWatcherRegistry fileWatcherRegistry = new FileWatcherRegistry();

        final Path path1 = createMockedPath();
        final Path path2 = createMockedPath();

        final FileWatchRegisterKey key1 = fileWatcherRegistry.register(path1, () -> {});
        final FileWatchRegisterKey key2 = fileWatcherRegistry.register(path2, () -> {});
        assertThat(fileWatcherRegistry.isRunning()).isTrue();

        fileWatcherRegistry.unregister(key1);
        assertThat(fileWatcherRegistry.isRunning()).isTrue();

        fileWatcherRegistry.unregister(key2);
        assertThat(fileWatcherRegistry.isRunning()).isFalse();
    }
}
