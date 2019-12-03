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

package com.linecorp.armeria.common;

/**
 * The common interface for HTTP/2 message objects, {@link HttpHeaders} and {@link HttpData}.
 * <br/>
 * 常规接口对于Http2的消息抽象载体，其具体实现类{@link HttpHeaders}，或者{@link HttpData}
 */
public interface HttpObject {

    /**
     * Tells whether the stream should be ended when writing this object. This can be useful for
     * <br/>
     * 告诉Stream是否把正在写入的对象完毕以后，结束写入。这个可以被用于
     * {@link HttpHeaders}-仅当响应或更高效的关闭Stream，随着最后一部分{@link HttpData}的写入。此方法仅仅对于{@link HttpObject}的写入有效，读是无效的。
     *
     * {@link HttpHeaders}-only responses or to more efficiently close the stream along with the last piece of
     * {@link HttpData}. This only has meaning for {@link HttpObject} writers, not readers.
     */
    boolean isEndOfStream();
}
