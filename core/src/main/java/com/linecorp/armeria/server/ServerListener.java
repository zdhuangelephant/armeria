/*
 * Copyright 2015 LINE Corporation
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

package com.linecorp.armeria.server;

/**
 * Listens to life cycle events of a {@link Server}.
 *
 * <br/>
 * {@link Server}生命周期监听者
 *
 * @see Server#addListener(ServerListener)
 * @see Server#removeListener(ServerListener)
 */
public interface ServerListener {
    /**
     * Invoked when a {@link Server} begins its startup procedure. Note that the {@link Server} will abort
     * its startup when a {@link ServerListener#serverStarting(Server)} throws an exception.
     * <br/>
     * 当服务启动的时候会调用。注意一点的是，Server如果在启动的时候被中止的话，则会抛出异常
     */
    void serverStarting(Server server) throws Exception;

    /**
     * Invoked when a {@link Server} finished its startup procedure successfully and it started to serve
     * incoming requests.
     * <br/>
     * 当服务启动成功并且开始处理请求会被调用
     */
    void serverStarted(Server server) throws Exception;

    /**
     * Invoked when a {@link Server} begins its shutdown procedure.
     * 当服务关机的时候会被调用
     */
    void serverStopping(Server server) throws Exception;

    /**
     * Invoked when a {@link Server} finished its shutdown procedure and it stopped to serve incoming requests.
     * <br/>
     * 当服务停服完成并且已经停止处理请求会被调用
     */
    void serverStopped(Server server) throws Exception;
}
