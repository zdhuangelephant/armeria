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

package com.linecorp.armeria.client.encoding;

import com.linecorp.armeria.common.HttpData;

/**
 * An interface for objects that apply HTTP content decoding to incoming {@link HttpData}.
 * Implement this interface to use content decoding schemes not built-in to the JDK.
 * 将Http内容适用于HttpData。实现这个接口的目的是解码内容，并不是内建的jdk
 */
public interface StreamDecoder {

    /**
     * Decodes an {@link HttpData} and returns the decoded {@link HttpData}.
     *  解码HttpData到一个新的HttpData
     */
    HttpData decode(HttpData obj);

    /**
     * Closes the decoder and returns any decoded data that may be left over.
     * 关闭解码器，并且返回可能剩余的HttpData
     */
    HttpData finish();
}
