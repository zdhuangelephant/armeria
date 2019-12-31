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

package com.linecorp.armeria.server.annotation;

import static com.linecorp.armeria.internal.DefaultValues.UNSPECIFIED;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.linecorp.armeria.common.HttpMethod;

/**
 * Annotation for mapping {@link HttpMethod#POST} onto specific method.
 *
 *
 * <pre>{@code
 * public class MyAnnotatedService {
 *     // 方法的参数在如下已经定义了
 *     @Post("/hello")
 *     public HttpResponse hello(MyRequestObject myRequestObject) { ... }
 * }
 *
 * // 定义上述请求方法内的，请求参数类型
 * public class MyRequestObject {
 *     @Param("name") // This field will be injected by the value of parameter "name".
 *     private String name;
 *
 *     @Header("age") // This field will be injected by the value of HTTP header "age".
 *     private int age;
 *
 *     // 这个字段将会被另外一个转化器注入数据
 *     @RequestObject // This field will be injected by another request converter.
 *     private MyAnotherRequestObject obj;
 *
 *     // 获取指定的gender参数
 *     // You can omit the value of @Param or @Header if you compiled your code with ``-parameters`` javac option.
 *     @Param         // This field will be injected by the value of parameter "gender".
 *     private String gender;
 *
 *     // 获取指定的头部  HTTP header "accept-language".
 *     @Header        // This field will be injected by the value of HTTP header "accept-language".
 *     private String acceptLanguage;
 *
 *     // @Param or @Header 分别获取参数或指定的头部数据
 *     @Param("address") // You can annotate a single parameter method with @Param or @Header.
 *     public void setAddress(String address) { ... }
 *
 *     // 构造方法。 通过@Param or @Header来指定
 *     @Header("id") // You can annotate a single parameter constructor with @Param or @Header.
 *     @Default("0")
 *     public MyRequestObject(long id) { ... }
 *
 *     // 方法@Param or @Header来指定
 *     // You can annotate all parameters of method or constructor with @Param or @Header.
 *     public void init(@Header("permissions") String permissions,
 *                      @Param("client-id") @Default("0") int clientId)
 * }
 *
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Post {

    /**
     * A path pattern for the annotated method.
     */
    String value() default UNSPECIFIED;
}
