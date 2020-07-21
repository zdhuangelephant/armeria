/*
 * Copyright 2018 LINE Corporation
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

import com.linecorp.armeria.internal.DefaultValues;

/**
 * An annotation used in annotated HTTP service. This describes:
 * <br/>
 * 一个用来注解Http service的注解。
 * <ul>
 *     <li>method parameters which are annotated with {@link Param} and {@link Header}.  被{@link Param},{@link Header}注解的方法形参参数</li>
 *     <li>methods which are annotated with {@link Path} or HTTP method annotations. 被{@link Path}标注的方法，或者被Http注解{@link Post}、{@link Get}等标注的方法</li>
 *     <li>classes which contain the methods above. 含有上述两种情况的实体类</li>
 * </ul>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE, ElementType.METHOD, ElementType.PARAMETER })
public @interface Description {

    /**
     * The description of a type, a field, a method or a parameter.
     * 关于某个类或某个方法的描述信息
     */
    String value() default DefaultValues.UNSPECIFIED;
}
