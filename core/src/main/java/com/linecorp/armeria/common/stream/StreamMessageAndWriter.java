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

package com.linecorp.armeria.common.stream;

/**
 * A type which is both a {@link StreamMessage} and a {@link StreamWriter}. This type is mainly used by tests
 * which need to exercise both functionality.
 * <br/>
 * 见名知意:
 *  该接口不仅继承了StreamMessage(即Publisher)，而且还继承了StreamWriter(Publisher和Subscriber之间传递的数据抽象)。至此这个接口了不得了，双重身份！。
 *
 * <br/>
 * note： 这个类主要用来测试些需要两种功能的场景下。
 */
interface StreamMessageAndWriter<T> extends StreamMessage<T>, StreamWriter<T> {
}
