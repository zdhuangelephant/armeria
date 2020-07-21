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
package com.linecorp.armeria.internal.annotation;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.linecorp.armeria.common.HttpParameters.EMPTY_PARAMETERS;
import static com.linecorp.armeria.internal.DefaultValues.getSpecifiedValue;
import static com.linecorp.armeria.internal.annotation.AnnotatedBeanFactoryRegistry.uniqueResolverSet;
import static com.linecorp.armeria.internal.annotation.AnnotatedBeanFactoryRegistry.warnRedundantUse;
import static com.linecorp.armeria.internal.annotation.AnnotatedElementNameUtil.findName;
import static com.linecorp.armeria.internal.annotation.AnnotatedHttpServiceFactory.findDescription;
import static com.linecorp.armeria.internal.annotation.AnnotatedHttpServiceTypeUtil.normalizeContainerType;
import static com.linecorp.armeria.internal.annotation.AnnotatedHttpServiceTypeUtil.stringToType;
import static com.linecorp.armeria.internal.annotation.AnnotatedHttpServiceTypeUtil.validateElementType;
import static com.linecorp.armeria.internal.annotation.AnnotationUtil.findDeclared;
import static java.util.Objects.requireNonNull;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Ascii;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.MapMaker;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpParameters;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.FallthroughException;
import com.linecorp.armeria.internal.annotation.AnnotatedBeanFactoryRegistry.BeanFactoryId;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.ByteArrayRequestConverterFunction;
import com.linecorp.armeria.server.annotation.Cookies;
import com.linecorp.armeria.server.annotation.Default;
import com.linecorp.armeria.server.annotation.Header;
import com.linecorp.armeria.server.annotation.JacksonRequestConverterFunction;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.RequestConverter;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.armeria.server.annotation.RequestObject;
import com.linecorp.armeria.server.annotation.StringRequestConverterFunction;

import io.netty.handler.codec.http.HttpConstants;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;

/**
 *  注解解析器
 */
final class AnnotatedValueResolver {
    private static final Logger logger = LoggerFactory.getLogger(AnnotatedValueResolver.class);

    // 全局默认的req转化者
    private static final List<RequestObjectResolver> defaultRequestConverters =
            ImmutableList.of((resolverContext, expectedResultType, beanFactoryId) ->
                                     AnnotatedBeanFactoryRegistry.find(beanFactoryId)
                                                                 .orElseThrow(
                                                                         RequestConverterFunction::fallthrough)
                                                                 .create(resolverContext),
                             RequestObjectResolver.of(new JacksonRequestConverterFunction()),
                             RequestObjectResolver.of(new StringRequestConverterFunction()),
                             RequestObjectResolver.of(new ByteArrayRequestConverterFunction()));

    private static final Object[] emptyArguments = new Object[0];

    /**
     * Returns an array of arguments which are resolved by each {@link AnnotatedValueResolver} of the
     * specified {@code resolvers}.
     */
    static Object[] toArguments(List<AnnotatedValueResolver> resolvers,
                                ResolverContext resolverContext) {
        requireNonNull(resolvers, "resolvers");
        requireNonNull(resolverContext, "resolverContext");
        if (resolvers.isEmpty()) {
            return emptyArguments;
        }
        return resolvers.stream().map(resolver -> resolver.resolve(resolverContext)).toArray();
    }

    /**
     * Returns a list of {@link RequestObjectResolver} that default request converters are added.
     * 返回一个待添加的{@link RequestObjectResolver}集合。
     */
    static List<RequestObjectResolver> toRequestObjectResolvers(
            List<RequestConverterFunction> converters) {
        final ImmutableList.Builder<RequestObjectResolver> builder = ImmutableList.builder();
        // Wrap every converters received from a user with a default object resolver.
        // com.linecorp.armeria.internal.annotation.AnnotatedValueResolver.RequestObjectResolver.of 方法的参数就是RequestConverterFunction类型的，所以才可以这么写;
        //
        converters.stream().map(RequestObjectResolver::of).forEach(builder::add);
        builder.addAll(defaultRequestConverters);
        return builder.build();
    }

    /**
     * Returns a list of {@link AnnotatedValueResolver} which is constructed with the specified
     * {@link Method}, {@code pathParams} and {@code objectResolvers}.
     * <br/>
     * 返回由指定(Method, pathParams, objectResolvers)构成的AnnotatedValueResolver集合
     */
    static List<AnnotatedValueResolver> ofServiceMethod(Method method, Set<String> pathParams,
                                                        List<RequestObjectResolver> objectResolvers) {
        return of(method, pathParams, objectResolvers, true, true);
    }

    /**
     * Returns a list of {@link AnnotatedValueResolver} which is constructed with the specified
     * {@code constructorOrMethod}, {@code pathParams} and {@code objectResolvers}.
     */
    static List<AnnotatedValueResolver> ofBeanConstructorOrMethod(Executable constructorOrMethod,
                                                                  Set<String> pathParams,
                                                                  List<RequestObjectResolver> objectResolvers) {
        return of(constructorOrMethod, pathParams, objectResolvers, false, false);
    }

    /**
     * Returns a list of {@link AnnotatedValueResolver} which is constructed with the specified
     * {@link Field}, {@code pathParams} and {@code objectResolvers}.
     */
    static Optional<AnnotatedValueResolver> ofBeanField(Field field, Set<String> pathParams,
                                                        List<RequestObjectResolver> objectResolvers) {
        // 'Field' is only used for converting a bean.
        // So we always need to pass 'implicitRequestObjectAnnotation' as false.
        return of(field, field, field.getType(), pathParams, objectResolvers, false);
    }

    /**
     * Returns a list of {@link AnnotatedValueResolver} which is constructed with the specified
     * {@link Executable}, {@code pathParams}, {@code objectResolvers} and
     * {@code implicitRequestObjectAnnotation}.
     * The {@link Executable} can be either {@link Constructor} or {@link Method}.
     *
     * <br/>
     * 返回{@link AnnotatedValueResolver}实例集合，其实例是由参数传入的{@link Executable}, {@code pathParams}, {@code objectResolvers} 和 {@code implicitRequestObjectAnnotation}构成 .
     * 这个{@link Executable}可能是{@link Constructor}也可能是{@link Method}
     *
     * @param isServiceMethod {@code true} if the {@code constructorOrMethod} is a service method.
     */
    private static List<AnnotatedValueResolver> of(Executable constructorOrMethod, Set<String> pathParams,
                                                   List<RequestObjectResolver> objectResolvers,
                                                   boolean implicitRequestObjectAnnotation,
                                                   boolean isServiceMethod) {
        // 获取方法的形参类型 方法的形参变量， 其是一个集合
        final Parameter[] parameters = constructorOrMethod.getParameters();
        if (parameters.length == 0) {
            throw new NoParameterException(constructorOrMethod.toGenericString());
        }
        //
        // Try to check whether it is an annotated constructor or method first. e.g.
        // 首先，先检查它是否是一个被注解的构造方法或普通方法. 例如:
        //
        // @Param
        // void setter(String name) { ... }
        //
        // In this case, we need to retrieve the value of @Param annotation from 'name' parameter,
        // not the constructor or method. Also 'String' type is used for the parameter.
        // 在上面的这个例子里， 我们需要获取被注解@param标记了的方法形参name的值。既不是构造器也不是普通方法，而是String类型的name的值
        //
        final Optional<AnnotatedValueResolver> resolver;
        if (isAnnotationPresent(constructorOrMethod)) {
            //仅仅允许形参只有一个的方法，如下的方法将会报错:
            // Only allow a single parameter on an annotated method. The followings cause an error:
            //
            // @Param
            // void setter(String name, int id, String address) { ... } // 这个方法的形参有多个，故而会报错
            //
            // @Param
            // void setter() { ... }   // 这个方法的形参一个也没有，故而会报错
            //
            if (parameters.length != 1) {
                throw new IllegalArgumentException("Only one parameter is allowed to an annotated method: " +
                                                   constructorOrMethod.toGenericString());
            }
            //过滤如下的情况case:
            // Filter out the cases like the following:
            //
            // @Param
            // void setter(@Header String name) { ... } // 方法只有一个形参，且被@Header注解标记
            //
            if (isAnnotationPresent(parameters[0])) {
                // 如果方法的形参是被@Header注解过的，则会抛出如下异常。 即不允许方法形参被@Header、@Param、@RequestObject注解标注
                throw new IllegalArgumentException("Both a method and parameter are annotated: " +
                                                   constructorOrMethod.toGenericString());
            }

            resolver = of(constructorOrMethod,
                          parameters[0], parameters[0].getType(), pathParams, objectResolvers,
                          implicitRequestObjectAnnotation);
        } else if (!isServiceMethod && parameters.length == 1 &&
                   !findDeclared(constructorOrMethod, RequestConverter.class).isEmpty()) {
            //
            // 过滤出如下的情况
            // Filter out the cases like the following:
            //
            // @RequestConverter(BeanConverter.class) // 既有注解修饰方法
            // void setter(@Header String name) { ... } // 同时，形参上也有@Header修饰形参
            //
            // 如果方法被@RequestConverter修饰的同时，其形参又被@Header修饰，则抛出异常
            if (isAnnotationPresent(parameters[0])) {
                throw new IllegalArgumentException("Both a method and parameter are annotated: " +
                                                   constructorOrMethod.toGenericString());
            }
            //
            // 如下才是正常的情况:
            // 隐式的调用@RequestObject的方法
            // Implicitly apply @RequestObject for the following case:
            //
            // @RequestConverter(BeanConverter.class)
            // void setter(Bean bean) { ... }
            //
            resolver = of(parameters[0], pathParams, objectResolvers, true);
        } else {
            // 那里没有任何注解，同时也没有@Default注解
            // There's no annotation. So there should be no @Default annotation, too.
            // e.g.
            // @Default("a")
            // void method1(ServiceRequestContext) { ... }
            //
            // 如果没有任何注解，同时却有@Default，则会抛出异常
            if (constructorOrMethod.isAnnotationPresent(Default.class)) {
                throw new IllegalArgumentException(
                        '@' + Default.class.getSimpleName() + " is not supported for: " +
                        constructorOrMethod.toGenericString());
            }

            resolver = Optional.empty();
        }
        // 如果在构造器或普通方法上没有注解，则尝试检查形参上是否有被注解的参数
        // If there is no annotation on the constructor or method, try to check whether it has
        // annotated parameters. e.g.
        //
        // void setter1(@Param String name) { ... } 这个方法的形参就被@Param注解
        // void setter2(@Param String name, @Header List<String> xForwardedFor) { ... } 这个方法的形参，就被@Param和@Header注解修饰
        //
        final List<AnnotatedValueResolver> list =
                resolver.<List<AnnotatedValueResolver>>map(ImmutableList::of).orElseGet(
                        () -> Arrays.stream(parameters)
                                    .map(p -> of(p, pathParams, objectResolvers,
                                                 implicitRequestObjectAnnotation))
                                    .filter(Optional::isPresent)
                                    .map(Optional::get)
                                    .collect(toImmutableList()));
        // 如果没有任何的注解， 则抛出没有被任何注解，修饰的参数的异常
        if (list.isEmpty()) {
            throw new NoAnnotatedParameterException(constructorOrMethod.toGenericString());
        }

        // 这个地方推测应该是一个参数就会对应着一个AnnotatedValueResolver实例
        // 对于上面的解析器和参数的 长度不一致的情况，我们需要判断，并抛出异常
        if (list.size() != parameters.length) {
            // There are parameters which cannot be resolved, so we cannot accept this constructor or method
            // as an annotated bean or method. We handle this case in two ways as follows.
            if (list.stream().anyMatch(r -> r.annotationType() != null)) {
                // If a user specify one of @Param, @Header or @RequestObject on the parameter list,
                // it clearly means that the user wants to convert the parameter into a bean. e.g.
                //
                // class BeanA {
                //     ...
                //     BeanA(@Param("a") int a, int b) { ... }
                // }
                throw new IllegalArgumentException("Unsupported parameter exists: " +
                                                   constructorOrMethod.toGenericString());
            } else {
                // But for the automatically injected types such as RequestContext and HttpRequest, etc.
                // it is not easy to understand what a user intends for. So we ignore that.
                //
                // class BeanB {
                //     ...
                //     BeanB(ServiceRequestContext ctx, int b) { ... }
                // }
                throw new NoAnnotatedParameterException("Unsupported parameter exists: " +
                                                        constructorOrMethod.toGenericString());
            }
        }
        //
        // 对于以下的情况， 我们同一个形参用了两边，我们就需要警告使用者
        // If there are annotations used more than once on the constructor or method, warn it.
        //
        // class RequestBean {
        //     // 构造器
        //     RequestBean(@Param("serialNo") Long serialNo, @Param("serialNo") Long serialNo2) { ... }
        // }
        //
        // or
        //
        // 普通方法
        // void setter(@Param("serialNo") Long serialNo, @Param("serialNo") Long serialNo2) { ... }
        //
        warnOnRedundantUse(constructorOrMethod, list);
        return list;
    }

    /**
     * Returns a list of {@link AnnotatedValueResolver} which is constructed with the specified
     * {@link Parameter}, {@code pathParams}, {@code objectResolvers} and
     * {@code implicitRequestObjectAnnotation}.
     */
    static Optional<AnnotatedValueResolver> of(Parameter parameter, Set<String> pathParams,
                                               List<RequestObjectResolver> objectResolvers,
                                               boolean implicitRequestObjectAnnotation) {
        return of(parameter, parameter, parameter.getType(), pathParams, objectResolvers,
                  implicitRequestObjectAnnotation);
    }

    /**
     * Creates a new {@link AnnotatedValueResolver} instance if the specified {@code annotatedElement} is
     * a component of {@link AnnotatedHttpService}.
     *
     * 如果参数annotatedElement是AnnotatedHttpService的一个组件，那么将会创建一个新的{@link AnnotatedValueResolver}实例
     *
     * @param annotatedElement an element which is annotated with a value specifier such as {@link Param} and
     *                         {@link Header}.  一个被{@link Param} 或 {@link Header}注解的元素，这个元素可能是类，也可能是方法
     *
     * @param typeElement      an element which is used for retrieving its type and name.  用来获取它的type和name的注解元素
     *
     * @param type             a type of the given {@link Parameter} or {@link Field}. It is a type of
     *                         the specified {@code typeElement} parameter.  Parameter或Field的类型。它是参数typeElement的类型。
     *
     * @param pathParams       a set of path variables.  一个路径集合
     *
     * @param objectResolvers  a list of {@link RequestObjectResolver} to be evaluated for the objects which
     *                         are annotated with {@link RequestObject} annotation. 一系列{@link RequestObjectResolver}的集合，集合内的元素用来解析被{@link RequestObject}注解，注解的对象。
     *
     * @param implicitRequestObjectAnnotation {@code true} if an element is always treated like it is annotated
     *                                        with {@link RequestObject} so that conversion is always done.
     *                                        {@code false} if an element has to be annotated with
     *                                        {@link RequestObject} explicitly to get converted.  true@当作实体被{@link RequestObject}注解，方便处理的; false@如果被
     */
    private static Optional<AnnotatedValueResolver> of(AnnotatedElement annotatedElement,
                                                       AnnotatedElement typeElement, Class<?> type,
                                                       Set<String> pathParams,
                                                       List<RequestObjectResolver> objectResolvers,
                                                       boolean implicitRequestObjectAnnotation) {
        requireNonNull(annotatedElement, "annotatedElement");
        requireNonNull(typeElement, "typeElement");
        requireNonNull(type, "type");
        requireNonNull(pathParams, "pathParams");
        requireNonNull(objectResolvers, "objectResolvers");

        // 拿到描述信息
        final String description = findDescription(annotatedElement);
        // 获取@Param 注解
        final Param param = annotatedElement.getAnnotation(Param.class);
        if (param != null) {
            final String name = findName(param, typeElement);
            // 如果解析出来一个路径，并且在参数pathParams中被包含
            if (pathParams.contains(name)) {
                return Optional.of(ofPathVariable(name, annotatedElement, typeElement,
                                                  type, description));
            } else {
                return Optional.of(ofHttpParameter(name, annotatedElement,
                                                   typeElement, type, description));
            }
        }

        final Header header = annotatedElement.getAnnotation(Header.class);
        if (header != null) {
            final String name = findName(header, typeElement);
            return Optional.of(ofHeader(name, annotatedElement, typeElement,
                                        type, description));
        }

        final RequestObject requestObject = annotatedElement.getAnnotation(RequestObject.class);
        if (requestObject != null) {
            // Find more request converters from a field or parameter.
            final List<RequestConverter> converters = findDeclared(typeElement, RequestConverter.class);
            return Optional.of(ofRequestObject(annotatedElement, type, pathParams,
                                               addToFirstIfExists(objectResolvers, converters),
                                               description));
        }

        // There should be no '@Default' annotation on 'annotatedElement' if 'annotatedElement' is
        // different from 'typeElement', because it was checked before calling this method.
        // So, 'typeElement' should be used when finding an injectable type because we need to check
        // syntactic errors like below:
        //
        // void method1(@Default("a") ServiceRequestContext ctx) { ... }
        //
        final AnnotatedValueResolver resolver = ofInjectableTypes(typeElement, type);
        if (resolver != null) {
            return Optional.of(resolver);
        }

        final List<RequestConverter> converters = findDeclared(typeElement, RequestConverter.class);
        if (!converters.isEmpty()) {
            // Apply @RequestObject implicitly when a @RequestConverter is specified.
            return Optional.of(ofRequestObject(annotatedElement, type, pathParams,
                                               addToFirstIfExists(objectResolvers, converters), description));
        }

        if (implicitRequestObjectAnnotation) {
            return Optional.of(ofRequestObject(annotatedElement, type, pathParams, objectResolvers,
                                               description));
        }

        return Optional.empty();
    }

    /**
     * 构造  List<RequestConverter> converters 内指定的所有Class对象的实例【通过反射】
     * @param resolvers
     * @param converters
     * @return
     */
    static List<RequestObjectResolver> addToFirstIfExists(List<RequestObjectResolver> resolvers,
                                                          List<RequestConverter> converters) {
        if (converters.isEmpty()) {
            return resolvers;
        }

        final ImmutableList.Builder<RequestObjectResolver> builder = new ImmutableList.Builder<>();
        // 将注解RequestConverter的值，全部遍历并且通过反射构造
        converters.forEach(c -> builder.add(RequestObjectResolver.of(
                AnnotatedHttpServiceFactory.getInstance(c.value()))));
        builder.addAll(resolvers);
        return builder.build();
    }

    /**
     * 判断参数 element上是否有@Param/@Header/@RequestObject注解
     * @param element
     * @return
     */
    private static boolean isAnnotationPresent(AnnotatedElement element) {
        return element.isAnnotationPresent(Param.class) ||
               element.isAnnotationPresent(Header.class) ||
               element.isAnnotationPresent(RequestObject.class);
    }

    private static void warnOnRedundantUse(Executable constructorOrMethod,
                                           List<AnnotatedValueResolver> list) {
        final Set<AnnotatedValueResolver> uniques = uniqueResolverSet();
        list.forEach(element -> {
            if (!uniques.add(element)) {
                warnRedundantUse(element, constructorOrMethod.toGenericString());
            }
        });
    }

    private static AnnotatedValueResolver ofPathVariable(String name,
                                                         AnnotatedElement annotatedElement,
                                                         AnnotatedElement typeElement, Class<?> type,
                                                         @Nullable String description) {
        return builder(annotatedElement, type)
                .annotationType(Param.class)
                .httpElementName(name)
                .typeElement(typeElement)
                .supportOptional(true)
                .pathVariable(true)
                .description(description)
                .resolver(resolver(ctx -> ctx.context().pathParam(name)))
                .build();
    }

    private static AnnotatedValueResolver ofHttpParameter(String name,
                                                          AnnotatedElement annotatedElement,
                                                          AnnotatedElement typeElement, Class<?> type,
                                                          @Nullable String description) {
        return builder(annotatedElement, type)
                .annotationType(Param.class)
                .httpElementName(name)
                .typeElement(typeElement)
                .supportOptional(true)
                .supportDefault(true)
                .supportContainer(true)
                .description(description)
                .aggregation(AggregationStrategy.FOR_FORM_DATA)
                .resolver(resolver(ctx -> ctx.httpParameters().getAll(name),
                                   () -> "Cannot resolve a value from HTTP parameter: " + name))
                .build();
    }

    private static AnnotatedValueResolver ofHeader(String name,
                                                   AnnotatedElement annotatedElement,
                                                   AnnotatedElement typeElement, Class<?> type,
                                                   @Nullable String description) {
        return builder(annotatedElement, type)
                .annotationType(Header.class)
                .httpElementName(name)
                .typeElement(typeElement)
                .supportOptional(true)
                .supportDefault(true)
                .supportContainer(true)
                .description(description)
                .resolver(resolver(
                        ctx -> ctx.request().headers().getAll(HttpHeaderNames.of(name)),
                        () -> "Cannot resolve a value from HTTP header: " + name))
                .build();
    }

    private static AnnotatedValueResolver ofRequestObject(AnnotatedElement annotatedElement,
                                                          Class<?> type, Set<String> pathParams,
                                                          List<RequestObjectResolver> objectResolvers,
                                                          @Nullable String description) {
        // To do recursive resolution like a bean inside another bean, the original object resolvers should
        // be passed into the AnnotatedBeanFactoryRegistry#register.
        final BeanFactoryId beanFactoryId = AnnotatedBeanFactoryRegistry.register(type, pathParams,
                                                                                  objectResolvers);
        return builder(annotatedElement, type)
                .annotationType(RequestObject.class)
                .description(description)
                .aggregation(AggregationStrategy.ALWAYS)
                .resolver(resolver(objectResolvers, beanFactoryId))
                .beanFactoryId(beanFactoryId)
                .build();
    }

    @Nullable
    private static AnnotatedValueResolver ofInjectableTypes(AnnotatedElement annotatedElement,
                                                            Class<?> type) {
        // Unwrap Optional type to support a parameter like 'Optional<RequestContext> ctx'
        // which is always non-empty.
        if (type != Optional.class) {
            return ofInjectableTypes0(annotatedElement, type, type);
        }

        final Type actual =
                ((ParameterizedType) parameterizedTypeOf(annotatedElement)).getActualTypeArguments()[0];
        final AnnotatedValueResolver resolver = ofInjectableTypes0(annotatedElement, type, actual);
        if (resolver != null) {
            logger.warn("Unnecessary Optional is used at '{}'", annotatedElement);
        }
        return resolver;
    }

    @Nullable
    private static AnnotatedValueResolver ofInjectableTypes0(AnnotatedElement annotatedElement,
                                                             Class<?> type, Type actual) {
        if (actual == RequestContext.class || actual == ServiceRequestContext.class) {
            return builder(annotatedElement, type)
                    .supportOptional(true)
                    .resolver((unused, ctx) -> ctx.context())
                    .build();
        }

        if (actual == Request.class || actual == HttpRequest.class) {
            return builder(annotatedElement, type)
                    .supportOptional(true)
                    .resolver((unused, ctx) -> ctx.request())
                    .build();
        }

        if (actual == HttpHeaders.class || actual == RequestHeaders.class) {
            return builder(annotatedElement, type)
                    .supportOptional(true)
                    .resolver((unused, ctx) -> ctx.request().headers())
                    .build();
        }

        if (actual == AggregatedHttpRequest.class) {
            return builder(annotatedElement, type)
                    .supportOptional(true)
                    .resolver((unused, ctx) -> ctx.aggregatedRequest())
                    .aggregation(AggregationStrategy.ALWAYS)
                    .build();
        }

        if (actual == HttpParameters.class) {
            return builder(annotatedElement, type)
                    .supportOptional(true)
                    .resolver((unused, ctx) -> ctx.httpParameters())
                    .aggregation(AggregationStrategy.FOR_FORM_DATA)
                    .build();
        }

        if (actual == Cookies.class) {
            return builder(annotatedElement, type)
                    .supportOptional(true)
                    .resolver((unused, ctx) -> {
                        final List<String> values = ctx.request().headers().getAll(HttpHeaderNames.COOKIE);
                        if (values.isEmpty()) {
                            return Cookies.copyOf(ImmutableSet.of());
                        }
                        final ImmutableSet.Builder<Cookie> cookies = ImmutableSet.builder();
                        values.stream()
                              .map(ServerCookieDecoder.STRICT::decode)
                              .forEach(cookies::addAll);
                        return Cookies.copyOf(cookies.build());
                    })
                    .build();
        }

        // Unsupported type.
        return null;
    }

    /**
     * Returns a single value resolver which retrieves a value from the specified {@code getter}
     * and converts it.
     *
     * resolver()的参数， ResolverContext是参数， String为返回值
     *
     * 返回一个BIFunction， 这个BIFunction的参数为AnnotatedValueResolver, ResolverContext 返回值为Object
     */
    private static BiFunction<AnnotatedValueResolver, ResolverContext, Object> resolver(Function<ResolverContext, String> getter) {
        return (resolver, ctx) -> resolver.convert(getter.apply(ctx));
    }

    /**
     * Returns a collection value resolver which retrieves a list of string from the specified {@code getter}
     * and adds them to the specified collection data type.
     */
    private static BiFunction<AnnotatedValueResolver, ResolverContext, Object>
    resolver(Function<ResolverContext, List<String>> getter, Supplier<String> failureMessageSupplier) {
        return (resolver, ctx) -> {
            final List<String> values = getter.apply(ctx);
            if (!resolver.hasContainer()) {
                if (values != null && !values.isEmpty()) {
                    return resolver.convert(values.get(0));
                }
                return resolver.defaultOrException();
            }

            try {
                assert resolver.containerType() != null;
                @SuppressWarnings("unchecked")
                final Collection<Object> resolvedValues =
                        (Collection<Object>) resolver.containerType().getDeclaredConstructor().newInstance();

                // Do not convert value here because the element type is String.
                if (values != null && !values.isEmpty()) {
                    values.stream().map(resolver::convert).forEach(resolvedValues::add);
                } else {
                    final Object defaultValue = resolver.defaultOrException();
                    if (defaultValue != null) {
                        resolvedValues.add(defaultValue);
                    }
                }
                return resolvedValues;
            } catch (Throwable cause) {
                throw new IllegalArgumentException(failureMessageSupplier.get(), cause);
            }
        };
    }

    /**
     * Returns a bean resolver which retrieves a value using request converters. If the target element
     * is an annotated bean, a bean factory of the specified {@link BeanFactoryId} will be used for creating an
     * instance.
     */
    private static BiFunction<AnnotatedValueResolver, ResolverContext, Object>
    resolver(List<RequestObjectResolver> objectResolvers, BeanFactoryId beanFactoryId) {
        return (resolver, ctx) -> {
            Object value = null;
            for (final RequestObjectResolver objectResolver : objectResolvers) {
                try {
                    value = objectResolver.convert(ctx, resolver.elementType(), beanFactoryId);
                    break;
                } catch (FallthroughException ignore) {
                    // Do nothing.
                } catch (Throwable cause) {
                    Exceptions.throwUnsafely(cause);
                }
            }
            if (value != null) {
                return value;
            }
            throw new IllegalArgumentException("No suitable request converter found for a @" +
                                               RequestObject.class.getSimpleName() + " '" +
                                               resolver.elementType().getSimpleName() + '\'');
        };
    }

    private static Type parameterizedTypeOf(AnnotatedElement element) {
        if (element instanceof Parameter) {
            // 得到参数化类型
            return ((Parameter) element).getParameterizedType();
        }
        if (element instanceof Field) {
            // 得到泛型参数类型
            return ((Field) element).getGenericType();
        }
        throw new IllegalArgumentException("Unsupported annotated element: " +
                                           element.getClass().getSimpleName());
    }

    /*******************************************以上都是该类的静态方法**************************************/
    @Nullable
    private final Class<? extends Annotation> annotationType;

    @Nullable
    private final String httpElementName;

    private final boolean isPathVariable;
    private final boolean shouldExist;
    private final boolean shouldWrapValueAsOptional;

    @Nullable
    private final Class<?> containerType;
    private final Class<?> elementType;

    @Nullable
    private final Object defaultValue;

    @Nullable
    private final String description;

    private final BiFunction<AnnotatedValueResolver, ResolverContext, Object> resolver;

    @Nullable
    private final EnumConverter<?> enumConverter;

    @Nullable
    private final BeanFactoryId beanFactoryId;

    private final AggregationStrategy aggregationStrategy;

    private static final ConcurrentMap<Class<?>, EnumConverter<?>> enumConverters = new MapMaker().makeMap();

    private AnnotatedValueResolver(@Nullable Class<? extends Annotation> annotationType,
                                   @Nullable String httpElementName,
                                   boolean isPathVariable, boolean shouldExist,
                                   boolean shouldWrapValueAsOptional,
                                   @Nullable Class<?> containerType, Class<?> elementType,
                                   @Nullable String defaultValue,
                                   @Nullable String description,
                                   BiFunction<AnnotatedValueResolver, ResolverContext, Object> resolver,
                                   @Nullable BeanFactoryId beanFactoryId,
                                   AggregationStrategy aggregationStrategy) {
        this.annotationType = annotationType;
        this.httpElementName = httpElementName;
        this.isPathVariable = isPathVariable;
        this.shouldExist = shouldExist;
        this.shouldWrapValueAsOptional = shouldWrapValueAsOptional;
        this.elementType = requireNonNull(elementType, "elementType");
        this.description = description;
        this.containerType = containerType;
        this.resolver = requireNonNull(resolver, "resolver");
        this.beanFactoryId = beanFactoryId;
        this.aggregationStrategy = requireNonNull(aggregationStrategy, "aggregationStrategy");
        enumConverter = enumConverter(elementType);

        // Must be called after initializing 'enumConverter'.
        this.defaultValue = defaultValue != null ? convert(defaultValue, elementType, enumConverter)
                                                 : null;
    }

    @Nullable
    private static EnumConverter<?> enumConverter(Class<?> elementType) {
        if (!elementType.isEnum()) {
            return null;
        }
        return enumConverters.computeIfAbsent(elementType, newElementType -> {
            logger.debug("Registered an Enum {}", newElementType);
            return new EnumConverter<>(newElementType.asSubclass(Enum.class));
        });
    }

    @Nullable
    Class<? extends Annotation> annotationType() {
        return annotationType;
    }

    @Nullable
    String httpElementName() {
        // Currently, this is non-null only if the element is one of the HTTP path variable,
        // parameter or header.
        return httpElementName;
    }

    boolean isPathVariable() {
        return isPathVariable;
    }

    boolean shouldExist() {
        return shouldExist;
    }

    boolean shouldWrapValueAsOptional() {
        return shouldWrapValueAsOptional;
    }

    @Nullable
    Class<?> containerType() {
        // 'List' or 'Set'
        return containerType;
    }

    Class<?> elementType() {
        return elementType;
    }

    @Nullable
    Object defaultValue() {
        return defaultValue;
    }

    @Nullable
    String description() {
        return description;
    }

    @Nullable
    BeanFactoryId beanFactoryId() {
        return beanFactoryId;
    }

    AggregationStrategy aggregationStrategy() {
        return aggregationStrategy;
    }

    boolean hasContainer() {
        return containerType != null &&
               (List.class.isAssignableFrom(containerType) || Set.class.isAssignableFrom(containerType));
    }

    Object resolve(ResolverContext ctx) {
        final Object resolved = resolver.apply(this, ctx);
        return shouldWrapValueAsOptional ? Optional.ofNullable(resolved)
                                         : resolved;
    }

    private static Object convert(String value, Class<?> elementType,
                                  @Nullable EnumConverter<?> enumConverter) {
        return enumConverter != null ? enumConverter.toEnum(value)
                                     : stringToType(value, elementType);
    }

    @Nullable
    private Object convert(@Nullable String value) {
        if (value == null) {
            return defaultOrException();
        }
        return convert(value, elementType, enumConverter);
    }

    @Nullable
    private Object defaultOrException() {
        if (!shouldExist) {
            // May return 'null' if no default value is specified.
            return defaultValue;
        }
        // 强制的参数是错误的
        throw new IllegalArgumentException("Mandatory parameter is missing: " + httpElementName);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
                          .add("annotation",
                               annotationType != null ? annotationType.getSimpleName() : "(none)")
                          .add("httpElementName", httpElementName)
                          .add("pathVariable", isPathVariable)
                          .add("shouldExist", shouldExist)
                          .add("shouldWrapValueAsOptional", shouldWrapValueAsOptional)
                          .add("elementType", elementType.getSimpleName())
                          .add("containerType",
                               containerType != null ? containerType.getSimpleName() : "(none)")
                          .add("defaultValue", defaultValue)
                          .add("defaultValueType",
                               defaultValue != null ? defaultValue.getClass().getSimpleName() : "(none)")
                          .add("description", description)
                          .add("resolver", resolver)
                          .add("enumConverter", enumConverter)
                          .toString();
    }

    /**
     * 生成Builder对象
     * @param annotatedElement
     * @param type
     * @return
     */
    private static Builder builder(AnnotatedElement annotatedElement, Type type) {
        return new Builder(annotatedElement, type);
    }

    /**
     * AnnotatedValueResolver的Builder构建类
     */
    private static final class Builder {
        private final AnnotatedElement annotatedElement;
        private final Type type;
        private AnnotatedElement typeElement;
        @Nullable
        private Class<? extends Annotation> annotationType;
        @Nullable
        private String httpElementName;
        private boolean pathVariable;
        private boolean supportContainer;
        private boolean supportOptional;
        private boolean supportDefault;
        @Nullable
        private String description;
        @Nullable
        private BiFunction<AnnotatedValueResolver, ResolverContext, Object> resolver;
        @Nullable
        private BeanFactoryId beanFactoryId;
        private AggregationStrategy aggregation = AggregationStrategy.NONE;

        private Builder(AnnotatedElement annotatedElement, Type type) {
            this.annotatedElement = requireNonNull(annotatedElement, "annotatedElement");
            this.type = requireNonNull(type, "type");
            typeElement = annotatedElement;
        }

        /**
         * Sets the annotation which is one of {@link Param}, {@link Header} or {@link RequestObject}.
         */
        private Builder annotationType(Class<? extends Annotation> annotationType) {
            assert annotationType == Param.class ||
                   annotationType == Header.class ||
                   annotationType == RequestObject.class : annotationType.getSimpleName();
            this.annotationType = annotationType;
            return this;
        }

        /**
         * Sets a name of the element.
         */
        private Builder httpElementName(String httpElementName) {
            this.httpElementName = httpElementName;
            return this;
        }

        /**
         * Sets whether this element is a path variable.
         */
        private Builder pathVariable(boolean pathVariable) {
            this.pathVariable = pathVariable;
            return this;
        }

        /**
         * Sets whether the value type can be a {@link List} or {@link Set}.
         */
        private Builder supportContainer(boolean supportContainer) {
            this.supportContainer = supportContainer;
            return this;
        }

        /**
         * Sets whether the value type can be wrapped by {@link Optional}.
         */
        private Builder supportOptional(boolean supportOptional) {
            this.supportOptional = supportOptional;
            return this;
        }

        /**
         * Sets whether the element can be annotated with {@link Default} annotation.
         */
        private Builder supportDefault(boolean supportDefault) {
            this.supportDefault = supportDefault;
            return this;
        }

        /**
         * Sets an {@link AnnotatedElement} which is used to infer its type.
         */
        private Builder typeElement(AnnotatedElement typeElement) {
            this.typeElement = typeElement;
            return this;
        }

        /**
         * Sets the description of the {@link AnnotatedElement}.
         */
        private Builder description(@Nullable String description) {
            this.description = description;
            return this;
        }

        /**
         * Sets a value resolver.
         */
        private Builder resolver(BiFunction<AnnotatedValueResolver, ResolverContext, Object> resolver) {
            this.resolver = resolver;
            return this;
        }

        private Builder beanFactoryId(BeanFactoryId beanFactoryId) {
            this.beanFactoryId = beanFactoryId;
            return this;
        }

        /**
         * Sets an {@link AggregationStrategy} for the element.
         */
        private Builder aggregation(AggregationStrategy aggregation) {
            this.aggregation = aggregation;
            return this;
        }

        /**
         * 解析  List<String> 诸如这种类型的参数,
         *  elementType 就是String.class
         *  containerType 就是ArrayList.class
         * @param parameterizedType
         * @param type
         * @param unwrapOptionalType
         * @return
         */
        private static Entry<Class<?>, Class<?>> resolveTypes(Type parameterizedType, Type type,
                                                              boolean unwrapOptionalType) {
            if (unwrapOptionalType) {
                // Unwrap once again so that a pattern like 'Optional<List<?>>' can be supported.
                assert parameterizedType instanceof ParameterizedType : String.valueOf(parameterizedType);
                parameterizedType = ((ParameterizedType) parameterizedType).getActualTypeArguments()[0];
            }

            final Class<?> elementType;
            final Class<?> containerType;
            if (parameterizedType instanceof ParameterizedType) {
                try {
                    // 元素类型
                    elementType =
                            (Class<?>) ((ParameterizedType) parameterizedType).getActualTypeArguments()[0];
                } catch (Throwable cause) {
                    throw new IllegalArgumentException("Invalid parameter type: " + parameterizedType, cause);
                }
                // 容器类型
                containerType = normalizeContainerType(
                        (Class<?>) ((ParameterizedType) parameterizedType).getRawType());
            } else {
                elementType = unwrapOptionalType ? (Class<?>) parameterizedType : (Class<?>) type;
                containerType = null;
            }
            return new SimpleImmutableEntry<>(containerType, validateElementType(elementType));
        }

        /**
         * build构建方法
         * @return
         */
        private AnnotatedValueResolver build() {
            checkArgument(resolver != null, "'resolver' should be specified");

            // Request convert may produce 'Optional<?>' value. But it is different from supporting
            // 'Optional' type. So if the annotation is 'RequestObject', 'shouldWrapValueAsOptional'
            // is always set as 'false'.
            final boolean shouldWrapValueAsOptional = type == Optional.class &&
                                                      annotationType != RequestObject.class;
            if (!supportOptional && shouldWrapValueAsOptional) {
                throw new IllegalArgumentException(
                        '@' + Optional.class.getSimpleName() + " is not supported for: " +
                        (annotationType != null ? annotationType.getSimpleName()
                                                : type.getTypeName()));
            }

            final boolean shouldExist;
            final String defaultValue;

            // 获取默认值
            final Default aDefault = annotatedElement.getAnnotation(Default.class);
            if (aDefault != null) {
                if (supportDefault) {
                    // Warn unusual usage. e.g. @Param @Default("a") Optional<String> param
                    if (shouldWrapValueAsOptional) {
                        // 'annotatedElement' can be one of constructor, field, method or parameter.
                        // So, it may be printed verbosely but it's okay because it provides where this message
                        // is caused.
                        logger.warn("@{} was used with '{}'. " +
                                    "Optional is redundant because the value is always present.",
                                    Default.class.getSimpleName(), annotatedElement);
                    }

                    shouldExist = false;
                    defaultValue = getSpecifiedValue(aDefault.value()).orElse(null);
                } else {
                    // Warn if @Default exists in an unsupported place.
                    final StringBuilder msg = new StringBuilder();
                    msg.append('@');
                    msg.append(Default.class.getSimpleName());
                    msg.append(" is redundant for ");
                    if (pathVariable) {
                        msg.append("path variable '").append(httpElementName).append('\'');
                    } else if (annotationType != null) {
                        msg.append("annotation @").append(annotationType.getSimpleName());
                    } else {
                        msg.append("type '").append(type.getTypeName()).append('\'');
                    }
                    msg.append(" because the value is always present.");
                    logger.warn(msg.toString());

                    shouldExist = !shouldWrapValueAsOptional;
                    // Set the default value to null just like it was not specified.
                    defaultValue = null;
                }
            } else {
                shouldExist = !shouldWrapValueAsOptional;
                // Set the default value to null if it was not specified.
                defaultValue = null;
            }

            if (pathVariable && !shouldExist) {
                logger.warn("Optional is redundant for path variable '{}' because the value is always present.",
                            httpElementName);
            }

            final Entry<Class<?>, Class<?>> types;
            if (annotationType == Param.class || annotationType == Header.class) {
                assert httpElementName != null;

                // The value annotated with @Param or @Header should be converted to the desired type,
                // so the type should be resolved here.
                final Type parameterizedType = parameterizedTypeOf(typeElement);
                types = resolveTypes(parameterizedType, type, shouldWrapValueAsOptional);

                // Currently a container type such as 'List' and 'Set' is allowed to @Header annotation
                // and HTTP parameters specified by @Param annotation.
                if (!supportContainer && types.getKey() != null) {
                    throw new IllegalArgumentException("Unsupported collection type: " + parameterizedType);
                }
            } else {
                assert type.getClass() == Class.class : String.valueOf(type);
                //
                // Here, 'type' should be one of the following types: type应该是如下几种类型的一种:
                // - RequestContext (or ServiceRequestContext)
                // - Request (or HttpRequest)
                // - AggregatedHttpRequest
                // - HttpParameters
                // - User classes which can be converted by request converter
                //
                // So the container type should be 'null'.
                //
                types = new SimpleImmutableEntry<>(null, (Class<?>) type);
            }

            return new AnnotatedValueResolver(annotationType, httpElementName, pathVariable, shouldExist,
                                              shouldWrapValueAsOptional, types.getKey(), types.getValue(),
                                              defaultValue, description, resolver,
                                              beanFactoryId, aggregation);
        }
    }

    /**
     * 验证是否是Form-data的mime类型
     * @param contentType
     * @return
     */
    private static boolean isFormData(@Nullable MediaType contentType) {
        return contentType != null && contentType.belongsTo(MediaType.FORM_DATA);
    }

    /**
     * 整合策略
     */
    enum AggregationStrategy {
        NONE, ALWAYS, FOR_FORM_DATA;

        /**
         * Returns whether the request should be aggregated.
         * <br/>
         * 校验传入的req是否应该被整合化一
         */
        static boolean aggregationRequired(AggregationStrategy strategy, HttpRequest req) {
            requireNonNull(strategy, "strategy");
            switch (strategy) {
                case ALWAYS:
                    return true;
                case FOR_FORM_DATA:
                    return isFormData(req.contentType());
            }
            return false;
        }

        /**
         * Returns {@link AggregationStrategy} which specifies how to aggregate the request
         * for injecting its parameters.
         */
        static AggregationStrategy from(List<AnnotatedValueResolver> resolvers) {
            AggregationStrategy strategy = NONE;
            for (final AnnotatedValueResolver r : resolvers) {
                switch (r.aggregationStrategy()) {
                    case ALWAYS:
                        return ALWAYS;
                    case FOR_FORM_DATA:
                        strategy = FOR_FORM_DATA;
                        break;
                }
            }
            return strategy;
        }
    }

    /**
     * A context which is used while resolving parameter values.
     * <br/>
     * 解析请求参数值的上下文对象
     */
    static class ResolverContext {
        /**
         * 服务端的上下文引用
         */
        private final ServiceRequestContext context;
        /**
         * 请求实体引用
         */
        private final HttpRequest request;

        @Nullable
        private final AggregatedHttpRequest aggregatedRequest;

        // 请求参数
        @Nullable
        private volatile HttpParameters httpParameters;

        ResolverContext(ServiceRequestContext context, HttpRequest request,
                        @Nullable AggregatedHttpRequest aggregatedRequest) {
            this.context = requireNonNull(context, "context");
            this.request = requireNonNull(request, "request");
            this.aggregatedRequest = aggregatedRequest;
        }

        ServiceRequestContext context() {
            return context;
        }

        HttpRequest request() {
            return request;
        }

        @Nullable
        AggregatedHttpRequest aggregatedRequest() {
            return aggregatedRequest;
        }

        HttpParameters httpParameters() {
            HttpParameters result = httpParameters;
            if (result == null) {
                synchronized (this) {
                    result = httpParameters;
                    if (result == null) {
                        httpParameters = result = httpParametersOf(context.query(),
                                                                   request.contentType(),
                                                                   aggregatedRequest);
                    }
                }
            }
            return result;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this).omitNullValues()
                              .add("context", context)
                              .add("request", request)
                              .add("aggregatedRequest", aggregatedRequest)
                              .add("httpParameters", httpParameters)
                              .toString();
        }

        /**
         * Returns a map of parameters decoded from a request.
         * <br/>
         * 返回一个参数的Map映射
         *
         * <p>Usually one of a query string of a URI or URL-encoded form data is specified in the request.
         * If both of them exist though, they would be decoded and merged into a parameter map.</p>
         *
         * <p>Names and values of the parameters would be decoded as UTF-8 character set.</p>
         * <br/>
         * 默认是UTF-8编码
         *
         * @see QueryStringDecoder#QueryStringDecoder(String, boolean)
         * @see HttpConstants#DEFAULT_CHARSET
         */
        private static HttpParameters httpParametersOf(@Nullable String query,
                                                       @Nullable MediaType contentType,
                                                       @Nullable AggregatedHttpRequest message) {
            try {
                Map<String, List<String>> parameters = null;
                if (query != null) {
                    parameters = new QueryStringDecoder(query, false).parameters();
                }

                if (message != null && isFormData(contentType)) {
                    // Respect 'charset' attribute of the 'content-type' header if it exists.
                    final String body = message.content(
                            contentType.charset().orElse(StandardCharsets.US_ASCII));
                    if (!body.isEmpty()) {
                        final Map<String, List<String>> p =
                                new QueryStringDecoder(body, false).parameters();
                        if (parameters == null) {
                            parameters = p;
                        } else if (p != null) {
                            parameters.putAll(p);
                        }
                    }
                }

                if (parameters == null || parameters.isEmpty()) {
                    return EMPTY_PARAMETERS;
                }

                return HttpParameters.copyOf(parameters);
            } catch (Exception e) {
                // If we failed to decode the query string, we ignore the exception raised here.
                // A missing parameter might be checked when invoking the annotated method.
                logger.debug("Failed to decode query string: {}", query, e);
                return EMPTY_PARAMETERS;
            }
        }
    }

    /**
     *  枚举容器工具，通过Key获取Value
     * @param <T>
     */
    private static final class EnumConverter<T extends Enum<T>> {
        private final boolean isCaseSensitiveEnum;

        private final Map<String, T> enumMap;

        /**
         * Creates an instance for the given {@link Enum} class.
         */
        EnumConverter(Class<T> enumClass) {
            final Set<T> enumInstances = EnumSet.allOf(enumClass);
            final Map<String, T> lowerCaseEnumMap = enumInstances.stream().collect(
                    toImmutableMap(e -> Ascii.toLowerCase(e.name()), Function.identity(), (e1, e2) -> e1));
            if (enumInstances.size() != lowerCaseEnumMap.size()) {
                enumMap = enumInstances.stream().collect(toImmutableMap(Enum::name, Function.identity()));
                isCaseSensitiveEnum = true;
            } else {
                enumMap = lowerCaseEnumMap;
                isCaseSensitiveEnum = false;
            }
        }

        /**
         * Returns the {@link Enum} value corresponding to the specified {@code str}.
         */
        T toEnum(String str) {
            final T result = enumMap.get(isCaseSensitiveEnum ? str : Ascii.toLowerCase(str));
            if (result != null) {
                return result;
            }

            throw new IllegalArgumentException(
                    "unknown enum value: " + str + " (expected: " + enumMap.values() + ')');
        }
    }



    /**
     * An interface to make a {@link RequestConverterFunction} be adapted for
     * {@link AnnotatedValueResolver} internal implementation.
     * <br/>
     * 一个可以使得{@link RequestConverterFunction}适配于{@link AnnotatedValueResolver}的内部接口
     */
    @FunctionalInterface // 当某个接口只有一个未实现的方法的时候
    interface RequestObjectResolver {
        /**
         * {@link RequestConverterFunction} 和 {@link AnnotatedValueResolver} 的连接枢纽方法。AnnotatedValueResolver 只会调用这个方法。
         * @param function
         * @return
         */
        static RequestObjectResolver of(RequestConverterFunction function) {
            // 内部类的替代写法， 这个地方实现了convert（resolverContext, expectedResultType, beanFactoryId）
            return (resolverContext, expectedResultType, beanFactoryId) -> {
                final AggregatedHttpRequest request = resolverContext.aggregatedRequest();
                if (request == null) {
                    throw new IllegalArgumentException(
                            "Cannot convert this request to an object because it is not aggregated.");
                }
                return function.convertRequest(resolverContext.context(), request, expectedResultType);
            };
        }

        // 该方式只有两个地方实现了此方法
        @Nullable
        Object convert(ResolverContext resolverContext, Class<?> expectedResultType,
                       @Nullable BeanFactoryId beanFactoryId) throws Throwable;
    }

    /**
     * A subtype of {@link IllegalArgumentException} which is raised when no annotated parameters exist
     * in a constructor or method.
     * <br/>
     * 没有注解的时候会被抛出该异常
     */
    static class NoAnnotatedParameterException extends IllegalArgumentException {

        private static final long serialVersionUID = -6003890710456747277L;

        NoAnnotatedParameterException(String name) {
            super("No annotated parameters found from: " + name);
        }
    }

    /**
     * A subtype of {@link NoAnnotatedParameterException} which is raised when no parameters exist in
     * a constructor or method.
     * <br/>
     * 有注解，但是注解内没有参数的时候，会被抛出该异常
     */
    static class NoParameterException extends NoAnnotatedParameterException {

        private static final long serialVersionUID = 3390292442571367102L;

        NoParameterException(String name) {
            super("No parameters found from: " + name);
        }
    }
}
