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

import javax.annotation.Nullable;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.internal.FallthroughException;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Converts an {@link AggregatedHttpRequest} to an object. The class implementing this interface would
 * be specified as a value of a {@link RequestConverter} annotation.
 * <br/>
 * 将{@link AggregatedHttpRequest}转化为一个Object, 实现该接口的类将会被指定为{@link RequestConverter}注解的值;
 * <br/>
 *
 * <ul>
 *     <li><h2>转换成Json的转换器是 {@link JacksonRequestConverterFunction}. 默认</h2></li>
 *     <li><h3>转换成字符串的转换器是 {@link StringRequestConverterFunction}</h3></li>
 *     <li> <h3>转换成字节的转换器是 {@link ByteArrayRequestConverterFunction}</h3></li>
 * </ul>
 *
 *
 *
 * @see RequestConverter
 * @see RequestObject
 *
 * <pre>{@code
 * public class ToEnglishConverter implements RequestConverterFunction {
 *     @Override
 *     public Object convertRequest(ServiceRequestContext ctx, AggregatedHttpRequest request,
 *                                  Class<?> expectedResultType) {
 *         if (expectedResultType == Greeting.class) {
 *             // 把request转变成javabean
 *             // Convert the request to a Java object.
 *             return new Greeting(translateToEnglish(request.contentUtf8()));
 *         }
 *
 *         // To the next request converter.  和刚刚看到的异常处理器一样的道理， 每当当前处理器无法处理的时候，就交给下一个来处理
 *         return RequestConverterFunction.fallthrough();
 *     }
 *
 *     // 翻译成英文
 *     private String translateToEnglish(String greetingInAnyLanguage) { ... }
 * }
 *
 *
 * // 指定转化器为上面定义的ToEnglishConverter类。
 * @RequestConverter(ToEnglishConverter.class)
 * public class MyAnnotatedService {
 *
 *     @Post("/hello")
 *     public HttpResponse hello(Greeting greeting) {
 *         // ToEnglishConverter will be used to convert a request.  应为类上标记了，所以会把request翻译成英语
 *         // ...
 *     }
 *
 *     @Post("/hola")
 *     @RequestConverter(ToSpanishConverter.class)
 *     public HttpResponse hola(Greeting greeting) {
 *         // ToSpanishConverter will be tried to convert a request first. 首先会把greeting翻译成英语
 *         // ToEnglishConverter will be used if ToSpanishConverter fell through. 如果翻译成西班牙语失败导致抛出异常，则默认就会被翻译成英语
 *         // ...
 *     }
 *
 *     @Post("/greet")
 *     public HttpResponse greet(RequestConverter(ToGermanConverter.class) Greeting greetingInGerman,
 *                               Greeting greetingInEnglish) {
 *         // For the 1st parameter 'greetingInGerman':    对于第一个参数 greetingInGerman
 *         // ToGermanConverter will be tried to convert a request first.  将会被翻译成德语
 *         // ToEnglishConverter will be used if ToGermanConverter fell through. 如果被翻译成德语失败抛出异常了，则会被翻译成英语
 *         //
 *         // For the 2nd parameter 'greetingInEnglish':  对于第二个参数 greetingInEnglish
 *         // ToEnglishConverter will be used to convert a request. 将会把它翻译成英语
 *         // ...
 *     }
 * }
 *
 * }</pre>
 */
@FunctionalInterface
public interface RequestConverterFunction {

    /**
     * Converts the specified {@code request} to an object of {@code expectedResultType}.
     * Calls {@link RequestConverterFunction#fallthrough()} or throws a {@link FallthroughException} if
     * this converter cannot convert the {@code request} to an object.
     * <br/>
     * 将传入的{@code request}转化为{@code expectedResultType}期望的类型实体。如果不能转化成功，则会抛出异常
     */
    @Nullable
    Object convertRequest(ServiceRequestContext ctx, AggregatedHttpRequest request,
                          Class<?> expectedResultType) throws Exception;

    /**
     * Throws a {@link FallthroughException} in order to try to convert the {@code request} to
     * an object by the next converter.
     *
     * 如果转换失败，则会抛出异常，但是抛出异常可以交给下一个Converter来进行转换，所以抛出异常并不是一件坏的事情
     */
    static <T> T fallthrough() {
        // Always throw the exception quietly.
        throw FallthroughException.get();
    }
}
