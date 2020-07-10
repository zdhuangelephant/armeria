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

package com.linecorp.armeria.common.util;

import static java.util.Objects.requireNonNull;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

/**
 * A skeletal {@link Listenable} implementation.
 * 一个可监听的实现抽象类，主要实现了添加监听，删除监听等主要方法
 */
public abstract class AbstractListenable<T> implements Listenable<T> {
    private final Set<Consumer<? super T>> updateListeners = new CopyOnWriteArraySet<>();

    /**
     * 此方法并不是从{@link Listenable}重写而来的。很奇怪，为什么不把notifyListeners定义在接口里面。 既然作者定义在这里，那就在这里吧。毕竟我们是菜比。
     * 突然想起来
     * Notify the new value changes to the listeners added via {@link #addListener(Consumer)}.
     */
    protected final void notifyListeners(T latestValue) {
        for (Consumer<? super T> listener : updateListeners) {
            listener.accept(latestValue);
        }
    }

    @Override
    public final void addListener(Consumer<? super T> listener) {
        requireNonNull(listener, "listener");
        updateListeners.add(listener);
    }

    @Override
    public final void removeListener(Consumer<?> listener) {
        requireNonNull(listener, "listener");
        updateListeners.remove(listener);
    }
}
