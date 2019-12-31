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
package com.linecorp.armeria.server.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An alias for {@code @Produces("text/event-stream")} and
 * {@code @ResponseConverter(ServerSentEventResponseConverterFunction.class)}.
 *
 * <prev>{@code
 * import com.linecorp.armeria.common.sse.ServerSentEvent;
 * import com.linecorp.armeria.server.annotation.Get;
 * import com.linecorp.armeria.server.annotation.ProducesEventStream;
 * import org.reactivestreams.Publisher;
 *
 * @Get("/sse")
 * @ProducesEventStream
 * public Publisher<ServerSentEvent> sse() {
 *     return Flux.just(ServerSentEvent.ofData("foo"), ServerSentEvent.ofData("bar"));
 * }
 * }</prev>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE, ElementType.METHOD })
@Produces("text/event-stream")
@ResponseConverter(ServerSentEventResponseConverterFunction.class)
public @interface ProducesEventStream {
}
