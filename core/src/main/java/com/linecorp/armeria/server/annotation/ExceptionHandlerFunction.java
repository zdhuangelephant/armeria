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

package com.linecorp.armeria.server.annotation;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.internal.FallthroughException;

/**
 * An interface for exception handler.
 * <br/>
 * 处理异常的处理者的接口声明
 * @see ExceptionHandler
 */
@FunctionalInterface
public interface ExceptionHandlerFunction {
    /**
     * Returns an {@link HttpResponse} which would be sent back to the client who sent the {@code req}.
     * Calls {@link ExceptionHandlerFunction#fallthrough()} or throws a {@link FallthroughException} if
     * this handler cannot handle the {@code cause}.
     * <br/>
     * 返回给发送端一个{@link HttpResponse}, 如果该Handler不能处理某些异常，则会调用{@link ExceptionHandlerFunction#fallthrough()}或者抛出{@link FallthroughException}
     */
    HttpResponse handleException(RequestContext ctx, HttpRequest req, Throwable cause);

    /**
     * Throws a {@link FallthroughException} in order to try to handle the {@link Throwable} by the next
     * handler.
     * <br/>
     * 为了使下一个Handler能够处理该Throwable，其将会抛出{@link FallthroughException}
     */
    static <T> T fallthrough() {
        // Always throw the exception quietly.
        throw FallthroughException.get();
    }
}
