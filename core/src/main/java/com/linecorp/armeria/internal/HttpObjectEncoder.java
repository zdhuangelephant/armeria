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

package com.linecorp.armeria.internal;

import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.util.ReferenceCountUtil;

/**
 * Converts an {@link HttpObject} into a protocol-specific object and writes it into a {@link Channel}.
 * <br/>
 * 将HttpObject编码为协议指定的obj，并且将其写入Channel内。其实现分为Http1ObjectEncoder和Http2ObjectEncoder，两种协议的支持。
 */
public abstract class HttpObjectEncoder {

    private volatile boolean closed;

    protected abstract Channel channel();

    protected EventLoop eventLoop() {
        return channel().eventLoop();
    }

    /**
     * Writes an {@link HttpHeaders}. 写入一个HttpHeaders
     */
    public final ChannelFuture writeHeaders(int id, int streamId, HttpHeaders headers, boolean endStream) {

        assert eventLoop().inEventLoop();

        if (closed) {
            return newClosedSessionFuture();
        }

        return doWriteHeaders(id, streamId, headers, endStream);
    }

    protected abstract ChannelFuture doWriteHeaders(int id, int streamId, HttpHeaders headers,
                                                    boolean endStream);

    /**
     * Writes an {@link HttpData}.
     * <br/>
     * 写入一个HttpData
     */
    public final ChannelFuture writeData(int id, int streamId, HttpData data, boolean endStream) {

        assert eventLoop().inEventLoop();

        if (closed) {
            ReferenceCountUtil.safeRelease(data);
            return newClosedSessionFuture();
        }

        return doWriteData(id, streamId, data, endStream);
    }

    protected abstract ChannelFuture doWriteData(int id, int streamId, HttpData data, boolean endStream);

    /**
     * Resets the specified stream. If the session protocol does not support multiplexing or the connection
     * is in unrecoverable state, the connection will be closed. For example, in an HTTP/1 connection, this
     * will lead the connection to be closed immediately or after the previous requests that are not reset.
     * <br/>
     * 重置指定的流。
     */
    public final ChannelFuture writeReset(int id, int streamId, Http2Error error) {

        if (closed) {
            return newClosedSessionFuture();
        }

        return doWriteReset(id, streamId, error);
    }

    protected abstract ChannelFuture doWriteReset(int id, int streamId, Http2Error error);

    /**
     * Releases the resources related with this encoder and fails any unfinished writes.
     * <br/>
     * 关闭与此encoder相关联的资源并且快速失败剩余那些没来得及写出的数据
     */
    public void close() {
        if (closed) {
            return;
        }
        // 置标记位为true
        closed = true;
        doClose();
    }

    protected abstract void doClose();

    protected final ChannelFuture newClosedSessionFuture() {
        return newFailedFuture(ClosedSessionException.get());
    }

    protected final ChannelFuture newFailedFuture(Throwable cause) {
        // 创建一个已经标记为失败的ChannelFuture，将会立即返回。 {@link ChannelFuture#isSuccess()}会立即返回false
        return channel().newFailedFuture(cause);
    }

    /**
     * 将HttpData转化为ByteBuf
     * @param data
     * @return
     */
    protected final ByteBuf toByteBuf(HttpData data) {
        if (data instanceof ByteBufHolder) {
            return ((ByteBufHolder) data).content();
        }
        final ByteBuf buf = channel().alloc().directBuffer(data.length(), data.length());
        buf.writeBytes(data.array());
        return buf;
    }
}
