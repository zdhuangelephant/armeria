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

package com.linecorp.armeria.common.stream;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.util.concurrent.EventExecutor;

/**
 * Options used when subscribing to a {@link StreamMessage}.
 * <br/>
 * 当订阅发布者的时候，一些可选项。
 *
 * @see StreamMessage#subscribe(Subscriber, SubscriptionOption...)
 * @see StreamMessage#subscribe(Subscriber, EventExecutor, SubscriptionOption...)
 */
public enum SubscriptionOption {

    /**
     * To receive the pooled {@link ByteBuf} and {@link ByteBufHolder} as is, without making a copy.
     * If you don't know what this means, do not specify this when you subscribe the {@link StreamMessage}.
     * <br/>
     * 当收到池化的对象时候，eg: {@link ByteBuf} and {@link ByteBufHolder}。不需要拷贝。
     * 如果不清楚的话，当订阅Publisher(即{@link StreamMessage})的时候可以不指定。
     */
    WITH_POOLED_OBJECTS,

    /**
     * To get notified by {@link Subscriber#onError(Throwable)} even when the {@link StreamMessage} is
     * {@linkplain Subscription#cancel() cancelled}.
     * <br/>
     * 当{@link StreamMessage}即Publisher被取消的时候。是否需要通知订阅着的{@link Subscriber#onError(Throwable)}，true需要通知。
     */
    NOTIFY_CANCELLATION
}
