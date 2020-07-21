/*
 *  Copyright 2018 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.internal.annotation;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Sets.toImmutableEnumSet;
import static com.linecorp.armeria.internal.annotation.AnnotatedValueResolver.toRequestObjectResolvers;
import static com.linecorp.armeria.internal.annotation.AnnotationUtil.findAll;
import static com.linecorp.armeria.internal.annotation.AnnotationUtil.findFirst;
import static com.linecorp.armeria.internal.annotation.AnnotationUtil.findFirstDeclared;
import static com.linecorp.armeria.internal.annotation.AnnotationUtil.getAllAnnotations;
import static com.linecorp.armeria.internal.annotation.AnnotationUtil.getAnnotations;
import static java.util.Objects.requireNonNull;
import static org.reflections.ReflectionUtils.getAllMethods;
import static org.reflections.ReflectionUtils.getConstructors;
import static org.reflections.ReflectionUtils.getMethods;
import static org.reflections.ReflectionUtils.withModifier;
import static org.reflections.ReflectionUtils.withName;
import static org.reflections.ReflectionUtils.withParametersCount;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.internal.ArmeriaHttpUtil;
import com.linecorp.armeria.internal.DefaultValues;
import com.linecorp.armeria.internal.annotation.AnnotatedValueResolver.NoParameterException;
import com.linecorp.armeria.internal.annotation.AnnotationUtil.FindOption;
import com.linecorp.armeria.server.DecoratingServiceFunction;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;
import com.linecorp.armeria.server.annotation.AdditionalHeader;
import com.linecorp.armeria.server.annotation.AdditionalTrailer;
import com.linecorp.armeria.server.annotation.ConsumeType;
import com.linecorp.armeria.server.annotation.Consumes;
import com.linecorp.armeria.server.annotation.Decorator;
import com.linecorp.armeria.server.annotation.DecoratorFactory;
import com.linecorp.armeria.server.annotation.DecoratorFactoryFunction;
import com.linecorp.armeria.server.annotation.Decorators;
import com.linecorp.armeria.server.annotation.Delete;
import com.linecorp.armeria.server.annotation.Description;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Head;
import com.linecorp.armeria.server.annotation.Options;
import com.linecorp.armeria.server.annotation.Order;
import com.linecorp.armeria.server.annotation.Patch;
import com.linecorp.armeria.server.annotation.Path;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.ProduceType;
import com.linecorp.armeria.server.annotation.Produces;
import com.linecorp.armeria.server.annotation.Put;
import com.linecorp.armeria.server.annotation.RequestConverter;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.armeria.server.annotation.RequestObject;
import com.linecorp.armeria.server.annotation.ResponseConverter;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;
import com.linecorp.armeria.server.annotation.StatusCode;
import com.linecorp.armeria.server.annotation.Trace;

/**
 * Builds a list of {@link AnnotatedHttpService}s from an {@link Object}.
 * This class is not supposed to be used by a user. Please check out the documentation
 * <a href="https://line.github.io/armeria/server-annotated-service.html#annotated-http-service">
 * Annotated HTTP Service</a> to use {@link AnnotatedHttpService}.
 */
public final class AnnotatedHttpServiceFactory {
    private static final Logger logger = LoggerFactory.getLogger(AnnotatedHttpServiceFactory.class);

    /**
     * An instance map for reusing converters, exception handlers and decorators.
     * 基于内存的缓存，是为了转换器、异常处理器、和装饰的重用。
     */
    private static final ConcurrentMap<Class<?>, Object> instanceCache = new ConcurrentHashMap<>();

    /**
     * A default {@link ExceptionHandlerFunction}.
     * <br/>
     * 默认的异常处理器
     */
    private static final ExceptionHandlerFunction defaultExceptionHandler = new DefaultExceptionHandler();

    /**
     * Mapping from HTTP method annotation to {@link HttpMethod}, like following.
     * 从http的请求方式，映射到具体的处理方法
     * <ul>
     *   <li>{@link Options} -> {@link HttpMethod#OPTIONS}
     *   <li>{@link Get} -> {@link HttpMethod#GET}
     *   <li>{@link Head} -> {@link HttpMethod#HEAD}
     *   <li>{@link Post} -> {@link HttpMethod#POST}
     *   <li>{@link Put} -> {@link HttpMethod#PUT}
     *   <li>{@link Patch} -> {@link HttpMethod#PATCH}
     *   <li>{@link Delete} -> {@link HttpMethod#DELETE}
     *   <li>{@link Trace} -> {@link HttpMethod#TRACE}
     * </ul>
     */
    private static final Map<Class<?>, HttpMethod> HTTP_METHOD_MAP =
            ImmutableMap.<Class<?>, HttpMethod>builder()
                    .put(Options.class, HttpMethod.OPTIONS)
                    .put(Get.class, HttpMethod.GET)
                    .put(Head.class, HttpMethod.HEAD)
                    .put(Post.class, HttpMethod.POST)
                    .put(Put.class, HttpMethod.PUT)
                    .put(Patch.class, HttpMethod.PATCH)
                    .put(Delete.class, HttpMethod.DELETE)
                    .put(Trace.class, HttpMethod.TRACE)
                    .build();

    /**
     * Returns the list of {@link AnnotatedHttpService} defined by {@link Path} and HTTP method annotations
     * from the specified {@code object}.
     * <br/>
     * 返回被{@link Path}注解标记或Http请求方法标记的{@link AnnotatedHttpService}的集合
     *
     * @param pathPrefix 路径前缀
     * @param object 目标实体
     * @param exceptionHandlersAndConverters  异常处理器/请求转换器/响应转换器 的集合
     */
    public static List<AnnotatedHttpServiceElement> find(String pathPrefix, Object object,
                                                         Iterable<?> exceptionHandlersAndConverters) {
        Builder<ExceptionHandlerFunction> exceptionHandlers = null;
        Builder<RequestConverterFunction> requestConverters = null;
        Builder<ResponseConverterFunction> responseConverters = null;

        for (final Object o : exceptionHandlersAndConverters) {
            boolean added = false;
            // 如果是异常处理器
            if (o instanceof ExceptionHandlerFunction) {
                if (exceptionHandlers == null) {
                    exceptionHandlers = ImmutableList.builder();
                }
                exceptionHandlers.add((ExceptionHandlerFunction) o);
                added = true;
            }
            // 如果是请求转换器
            if (o instanceof RequestConverterFunction) {
                if (requestConverters == null) {
                    requestConverters = ImmutableList.builder();
                }
                requestConverters.add((RequestConverterFunction) o);
                added = true;
            }
            // 如果是响应转换器
            if (o instanceof ResponseConverterFunction) {
                if (responseConverters == null) {
                    responseConverters = ImmutableList.builder();
                }
                responseConverters.add((ResponseConverterFunction) o);
                added = true;
            }
            // 如果一个都没有合格的，则就是抛出异常，代码空转了！
            if (!added) {
                throw new IllegalArgumentException(o.getClass().getName() +
                                                   " is neither an exception handler nor a converter.");
            }
        }

        // 非空赋值
        final List<ExceptionHandlerFunction> exceptionHandlerFunctions =
                exceptionHandlers != null ? exceptionHandlers.build() : ImmutableList.of();
        final List<RequestConverterFunction> requestConverterFunctions =
                requestConverters != null ? requestConverters.build() : ImmutableList.of();
        final List<ResponseConverterFunction> responseConverterFunctions =
                responseConverters != null ? responseConverters.build() : ImmutableList.of();

        /**
         * 在传入的object内，收集符合条件的方法(eg: 必须是public、方法被{@link Path}或者Http请求注解，注解过)。并根据注解内的Order属性进行自然排序
         */
        final List<Method> methods = requestMappingMethods(object);
        return methods.stream()
                      .map((Method method) -> create(pathPrefix, object, method, exceptionHandlerFunctions,
                                                     requestConverterFunctions, responseConverterFunctions))
                      .collect(toImmutableList());
    }

    private static HttpStatus defaultResponseStatus(Optional<HttpStatus> defaultResponseStatus,
                                                    Method method) {
        return defaultResponseStatus.orElseGet(() -> {
            // Set a default HTTP status code for a response depending on the return type of the method.
            final Class<?> returnType = method.getReturnType();
            return returnType == Void.class ||
                   returnType == void.class ? HttpStatus.NO_CONTENT : HttpStatus.OK;
        });
    }

    private static <T extends Annotation> void setAdditionalHeader(HttpHeadersBuilder headers,
                                                                   AnnotatedElement element,
                                                                   String clsAlias,
                                                                   String elementAlias,
                                                                   String level,
                                                                   Class<T> annotation,
                                                                   Function<T, String> nameGetter,
                                                                   Function<T, String[]> valueGetter) {
        requireNonNull(headers, "headers");
        requireNonNull(element, "element");
        requireNonNull(level, "level");

        final Set<String> addedHeaderSets = new HashSet<>();
        findAll(element, annotation).forEach(header -> {
            final String name = nameGetter.apply(header);
            final String[] value = valueGetter.apply(header);

            if (addedHeaderSets.contains(name)) {
                logger.warn("The additional {} named '{}' at '{}' is set at the same {} level already;" +
                            "ignoring.",
                            clsAlias, name, elementAlias, level);
                return;
            }
            headers.set(HttpHeaderNames.of(name), value);
            addedHeaderSets.add(name);
        });
    }

    /**
     * Returns an {@link AnnotatedHttpService} instance defined to {@code method} of {@code object} using
     * {@link Path} annotation.
     *
     * <br/>
     * 返回 被注解{@link Path}修饰的{@link AnnotatedHttpService}实例
     */
    private static AnnotatedHttpServiceElement create(String pathPrefix, Object object, Method method,
                                                      List<ExceptionHandlerFunction> baseExceptionHandlers,
                                                      List<RequestConverterFunction> baseRequestConverters,
                                                      List<ResponseConverterFunction> baseResponseConverters) {

        // 获取参数method中都是有哪些http注解， eg: Get/Post/Options/...等
        final Set<Annotation> methodAnnotations = httpMethodAnnotations(method);
        // 不允许是空的，否则抛出异常
        if (methodAnnotations.isEmpty()) {
            throw new IllegalArgumentException("HTTP Method specification is missing: " + method.getName());
        }

        final Set<HttpMethod> methods = toHttpMethods(methodAnnotations);
        // 如果是空的， 则抛出异常
        if (methods.isEmpty()) {
            throw new IllegalArgumentException(method.getDeclaringClass().getName() + '#' + method.getName() +
                                               " must have an HTTP method annotation.");
        }

        final Class<?> clazz = object.getClass();
        // 从http注解 Post或Get或Path中拿到请求定义的URI
        final String pattern = findPattern(method, methodAnnotations);

        /**
         * 构造请求路由
         * 1、请求方法
         * 2、请求路径URI(包括前缀)
         * 3、请求的MediaType
          */
        final Route route = Route.builder()
                                  // 请求路径前缀 和 具体的请求URI
                                 .pathWithPrefix(pathPrefix, pattern)
                                 // 请求方法
                                 .methods(methods)
                                 .consumes(consumableMediaTypes(method, clazz))
                                 .produces(producibleMediaTypes(method, clazz))
                                 .build();
        // TODO 待分析 2020-7-13 18:52:30
        final List<ExceptionHandlerFunction> eh =
                getAnnotatedInstances(method, clazz, ExceptionHandler.class, ExceptionHandlerFunction.class)
                        .addAll(baseExceptionHandlers).add(defaultExceptionHandler).build();
        final List<RequestConverterFunction> req =
                getAnnotatedInstances(method, clazz, RequestConverter.class, RequestConverterFunction.class)
                        .addAll(baseRequestConverters).build();
        final List<ResponseConverterFunction> res =
                getAnnotatedInstances(method, clazz, ResponseConverter.class, ResponseConverterFunction.class)
                        .addAll(baseResponseConverters).build();

        List<AnnotatedValueResolver> resolvers;
        try {
            resolvers = AnnotatedValueResolver.ofServiceMethod(method, route.paramNames(),
                                                               toRequestObjectResolvers(req));
        } catch (NoParameterException ignored) {
            // Allow no parameter like below:
            //
            // @Get("/")
            // public String method1() { ... }
            //
            resolvers = ImmutableList.of();
        }

        final Set<String> expectedParamNames = route.paramNames();
        final Set<String> requiredParamNames =
                resolvers.stream()
                         .filter(AnnotatedValueResolver::isPathVariable)
                         .map(AnnotatedValueResolver::httpElementName)
                         .collect(Collectors.toSet());

        if (!expectedParamNames.containsAll(requiredParamNames)) {
            final Set<String> missing = Sets.difference(requiredParamNames, expectedParamNames);
            throw new IllegalArgumentException("cannot find path variables: " + missing);
        }

        // Warn unused path variables only if there's no '@RequestObject' annotation.
        if (resolvers.stream().noneMatch(r -> r.annotationType() == RequestObject.class) &&
            !requiredParamNames.containsAll(expectedParamNames)) {
            final Set<String> missing = Sets.difference(expectedParamNames, requiredParamNames);
            logger.warn("Some path variables of the method '" + method.getName() +
                        "' of the class '" + clazz.getName() +
                        "' do not have their corresponding parameters annotated with @Param. " +
                        "They would not be automatically injected: " + missing);
        }

        final Optional<HttpStatus> defaultResponseStatus = findFirst(method, StatusCode.class)
                .map(code -> {
                    final int statusCode = code.value();
                    checkArgument(statusCode >= 0,
                                  "invalid HTTP status code: %s (expected: >= 0)", statusCode);
                    return HttpStatus.valueOf(statusCode);
                });
        final ResponseHeadersBuilder defaultHeaders =
                ResponseHeaders.builder(defaultResponseStatus(defaultResponseStatus, method));

        final HttpHeadersBuilder defaultTrailers = HttpHeaders.builder();
        final String classAlias = clazz.getName();
        final String methodAlias = String.format("%s.%s()", classAlias, method.getName());
        setAdditionalHeader(defaultHeaders, clazz, "header", classAlias, "class", AdditionalHeader.class,
                            AdditionalHeader::name, AdditionalHeader::value);
        setAdditionalHeader(defaultHeaders, method, "header", methodAlias, "method", AdditionalHeader.class,
                            AdditionalHeader::name, AdditionalHeader::value);
        setAdditionalHeader(defaultTrailers, clazz, "trailer", classAlias, "class",
                            AdditionalTrailer.class, AdditionalTrailer::name, AdditionalTrailer::value);
        setAdditionalHeader(defaultTrailers, method, "trailer", methodAlias, "method",
                            AdditionalTrailer.class, AdditionalTrailer::name, AdditionalTrailer::value);

        if (ArmeriaHttpUtil.isContentAlwaysEmpty(defaultHeaders.status()) &&
            !defaultTrailers.isEmpty()) {
            logger.warn("A response with HTTP status code '{}' cannot have a content. " +
                        "Trailers defined at '{}' might be ignored.",
                        defaultHeaders.status().code(), methodAlias);
        }

        // A CORS preflight request can be received because we handle it specially. The following
        // decorator will prevent the service from an unexpected request which has OPTIONS method.
        final Function<Service<HttpRequest, HttpResponse>,
                ? extends Service<HttpRequest, HttpResponse>> initialDecorator;
        if (methods.contains(HttpMethod.OPTIONS)) {
            initialDecorator = Function.identity();
        } else {
            initialDecorator = delegate -> new SimpleDecoratingHttpService(delegate) {
                @Override
                public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                    if (req.method() == HttpMethod.OPTIONS) {
                        // This must be a CORS preflight request.
                        throw HttpStatusException.of(HttpStatus.FORBIDDEN);
                    }
                    return delegate().serve(ctx, req);
                }
            };
        }
        return new AnnotatedHttpServiceElement(route, new AnnotatedHttpService(object, method, resolvers,
                                                                               eh, res, route,
                                                                               defaultHeaders.build(),
                                                                               defaultTrailers.build()),
                                               decorator(method, clazz, initialDecorator));
    }

    /**
     * Returns the list of {@link Path} annotated methods.
     * <br/>
     * 返回被{@link Path}注解标记的方法
     *
     * @param object 含有注解的实体类
     */
    private static List<Method> requestMappingMethods(Object object) {
        /**
         * 1、首先根据修饰符获取指定类的所有公共方法
         * 2、之后，在父类中也要递归遍历获取符合条件的方法
         * 3、然后根据注解中携带的Order值，进行自然排序
         *
         */
        return getAllMethods(object.getClass(), withModifier(Modifier.PUBLIC))
                .stream()
                // Lookup super classes just in case if the object is a proxy. 万一是个代理类，所以要查找父类
                .filter(m -> getAnnotations(m, FindOption.LOOKUP_SUPER_CLASSES)
                        .stream()
                        .map(Annotation::annotationType)
                        // 查看是否有被注解Path修饰或者是否有被Http method修饰
                        .anyMatch(a -> a == Path.class ||
                                       HTTP_METHOD_MAP.containsKey(a)))
                // 根据注解Order，进行顺序排序
                .sorted(Comparator.comparingInt(AnnotatedHttpServiceFactory::order))
                .collect(toImmutableList());
    }

    /**
     * Returns the value of the order of the {@link Method}. The order could be retrieved from {@link Order}
     * annotation. 0 would be returned if there is no specified {@link Order} annotation.
     * <br/>
     * 返回方法返回的顺序。这个顺序从注解{@link Order}内获取。0表示没有指定。
     */
    private static int order(Method method) {
        final Order order = findFirst(method, Order.class).orElse(null);
        return order != null ? order.value() : 0;
    }

    /**
     * Returns {@link Set} of HTTP method annotations of a given method.
     * The annotations are as follows.
     * 返回参数method内有哪些如下的http注解、并且返回
     *
     * @see Options
     * @see Get
     * @see Head
     * @see Post
     * @see Put
     * @see Patch
     * @see Delete
     * @see Trace
     */
    private static Set<Annotation> httpMethodAnnotations(Method method) {
        return getAnnotations(method, FindOption.LOOKUP_SUPER_CLASSES)
                .stream()
                .filter(annotation -> HTTP_METHOD_MAP.containsKey(annotation.annotationType()))
                .collect(Collectors.toSet());
    }

    /**
     * Returns {@link Set} of {@link HttpMethod}s mapped to HTTP method annotations.
     * <br/>
     * 返回http注解 --> http方法的映射
     *
     * @see Options
     * @see Get
     * @see Head
     * @see Post
     * @see Put
     * @see Patch
     * @see Delete
     * @see Trace
     */
    private static Set<HttpMethod> toHttpMethods(Set<Annotation> annotations) {
        return annotations.stream()
                          .map(annotation -> HTTP_METHOD_MAP.get(annotation.annotationType()))
                          .filter(Objects::nonNull)
                          .collect(toImmutableEnumSet());
    }

    /**
     * Returns the set of {@link MediaType}s specified by {@link Consumes} annotation.
     * <br/>
     * 先从方法中获取 定义的Consumer; 如果没有定义，则继承用类级别的Consumer
     */
    private static Set<MediaType> consumableMediaTypes(Method method, Class<?> clazz) {
        List<Consumes> consumes = findAll(method, Consumes.class);
        List<ConsumeType> consumeTypes = findAll(method, ConsumeType.class);

        if (consumes.isEmpty() && consumeTypes.isEmpty()) {
            consumes = findAll(clazz, Consumes.class);
            consumeTypes = findAll(clazz, ConsumeType.class);
        }

        final List<MediaType> types =
                Stream.concat(consumes.stream().map(Consumes::value),
                              consumeTypes.stream().map(ConsumeType::value))
                      .map(MediaType::parse)
                      .collect(toImmutableList());
        return listToSet(types, Consumes.class);
    }

    /**
     * Returns the list of {@link MediaType}s specified by {@link Produces} annotation.
     * <br/>
     * 返回之指定的{@link Produces} MediaType类型；
     * 先获取方法级别的，如果方法级别的为空，则获取类级别的
     */
    private static Set<MediaType> producibleMediaTypes(Method method, Class<?> clazz) {
        List<Produces> produces = findAll(method, Produces.class);
        List<ProduceType> produceTypes = findAll(method, ProduceType.class);

        if (produces.isEmpty() && produceTypes.isEmpty()) {
            produces = findAll(clazz, Produces.class);
            produceTypes = findAll(clazz, ProduceType.class);
        }

        final List<MediaType> types =
                Stream.concat(produces.stream().map(Produces::value),
                              produceTypes.stream().map(ProduceType::value))
                      .map(MediaType::parse)
                      .peek(type -> {
                          if (type.hasWildcard()) {
                              throw new IllegalArgumentException(
                                      "Producible media types must not have a wildcard: " + type);
                          }
                      })
                      .collect(toImmutableList());
        return listToSet(types, Produces.class);
    }

    /**
     * Converts the list of {@link MediaType}s to a set. It raises an {@link IllegalArgumentException} if the
     * list has duplicate elements.
     */
    private static Set<MediaType> listToSet(List<MediaType> types, Class<?> annotationClass) {
        final Set<MediaType> set = new LinkedHashSet<>();
        for (final MediaType type : types) {
            if (!set.add(type)) {
                throw new IllegalArgumentException(
                        "Duplicated media type for @" + annotationClass.getSimpleName() + ": " + type);
            }
        }
        return ImmutableSet.copyOf(set);
    }

    /**
     * Returns a specified path pattern. The path pattern might be specified by {@link Path} or
     * HTTP method annotations such as {@link Get} and {@link Post}.
     * <br/>
     * 返回一个具体的请求路径. 这个路径可能会在 {@link Get} and {@link Post} and {@link Path}
     */
    private static String findPattern(Method method, Set<Annotation> methodAnnotations) {
        String pattern = findFirst(method, Path.class).map(Path::value)
                                                      .orElse(null);
        for (Annotation a : methodAnnotations) {
            // 获取注解上的value值，即请求映射uri
            final String p = (String) invokeValueMethod(a);
            // 如果是nil值，哈哈人家框架自定义的
            if (DefaultValues.isUnspecified(p)) {
                continue;
            }
            // 只能提取一个请求uri，如果出现第二个就抛出异常了
            checkArgument(pattern == null,
                          "Only one path can be specified. (" + pattern + ", " + p + ')');
            pattern = p;
        }
        // 如果 Post、Get、Path等注解中都没有获取到请求uri， 你特么逗我呢? 不想搭理你，给你抛个异常玩去吧，
        if (pattern == null || pattern.isEmpty()) {
            throw new IllegalArgumentException(
                    "A path pattern should be specified by @Path or HTTP method annotations.");
        }
        return pattern;
    }

    /**
     * Returns a decorator chain which is specified by {@link Decorator} annotations and user-defined
     * decorator annotations.
     */
    private static Function<Service<HttpRequest, HttpResponse>,
            ? extends Service<HttpRequest, HttpResponse>> decorator(
            Method method, Class<?> clazz,
            Function<Service<HttpRequest, HttpResponse>,
                    ? extends Service<HttpRequest, HttpResponse>> initialDecorator) {

        final List<DecoratorAndOrder> decorators = collectDecorators(clazz, method);

        Function<Service<HttpRequest, HttpResponse>,
                ? extends Service<HttpRequest, HttpResponse>> decorator = initialDecorator;
        for (int i = decorators.size() - 1; i >= 0; i--) {
            final DecoratorAndOrder d = decorators.get(i);
            decorator = decorator.andThen(d.decorator());
        }
        return decorator;
    }

    /**
     * Returns a decorator list which is specified by {@link Decorator} annotations and user-defined
     * decorator annotations.
     */
    @VisibleForTesting
    static List<DecoratorAndOrder> collectDecorators(Class<?> clazz, Method method) {
        final List<DecoratorAndOrder> decorators = new ArrayList<>();

        // Class-level decorators are applied before method-level decorators.
        collectDecorators(decorators, getAllAnnotations(clazz));
        collectDecorators(decorators, getAllAnnotations(method));

        // Sort decorators by "order" attribute values.
        decorators.sort(Comparator.comparing(DecoratorAndOrder::order));

        return decorators;
    }

    /**
     * Adds decorators to the specified {@code list}. Decorators which are annotated with {@link Decorator}
     * and user-defined decorators will be collected.
     */
    private static void collectDecorators(List<DecoratorAndOrder> list, List<Annotation> annotations) {
        if (annotations.isEmpty()) {
            return;
        }

        // Respect the order of decorators which is specified by a user. The first one is first applied
        // for most of the cases. But if @Decorator and user-defined decorators are specified in a mixed order,
        // the specified order and the applied order can be different. To overcome this problem, we introduce
        // "order" attribute to @Decorator annotation to sort decorators. If a user-defined decorator
        // annotation has "order" attribute, it will be also used for sorting.
        for (final Annotation annotation : annotations) {
            if (annotation instanceof Decorator) {
                final Decorator d = (Decorator) annotation;
                list.add(new DecoratorAndOrder(d, newDecorator(d), d.order()));
                continue;
            }

            if (annotation instanceof Decorators) {
                final Decorator[] decorators = ((Decorators) annotation).value();
                for (final Decorator d : decorators) {
                    list.add(new DecoratorAndOrder(d, newDecorator(d), d.order()));
                }
                continue;
            }

            DecoratorAndOrder udd = userDefinedDecorator(annotation);
            if (udd != null) {
                list.add(udd);
                continue;
            }

            // If user-defined decorators are repeatable and they are specified more than once.
            try {
                final Method method = Iterables.getFirst(getMethods(annotation.annotationType(),
                                                                    withName("value")), null);
                assert method != null : "No 'value' method is found from " + annotation;
                final Annotation[] decorators = (Annotation[]) method.invoke(annotation);
                for (final Annotation decorator : decorators) {
                    udd = userDefinedDecorator(decorator);
                    if (udd == null) {
                        break;
                    }
                    list.add(udd);
                }
            } catch (Throwable ignore) {
                // The annotation may be a container of a decorator or may be not, so we just ignore
                // any exception from this clause.
            }
        }
    }

    /**
     * Returns a decorator with its order if the specified {@code annotation} is one of the user-defined
     * decorator annotation.
     */
    @Nullable
    private static DecoratorAndOrder userDefinedDecorator(Annotation annotation) {
        // User-defined decorator MUST be annotated with @DecoratorFactory annotation.
        final DecoratorFactory d =
                findFirstDeclared(annotation.annotationType(), DecoratorFactory.class).orElse(null);
        if (d == null) {
            return null;
        }

        // In case of user-defined decorator, we need to create a new decorator from its factory.
        @SuppressWarnings("unchecked")
        final DecoratorFactoryFunction<Annotation> factory = getInstance(d, DecoratorFactoryFunction.class);

        // If the annotation has "order" attribute, we can use it when sorting decorators.
        int order = 0;
        try {
            final Method method = Iterables.getFirst(getMethods(annotation.annotationType(),
                                                                withName("order")), null);
            if (method != null) {
                final Object value = method.invoke(annotation);
                if (value instanceof Integer) {
                    order = (Integer) value;
                }
            }
        } catch (Throwable ignore) {
            // A user-defined decorator may not have an 'order' attribute.
            // If it does not exist, '0' is used by default.
        }
        return new DecoratorAndOrder(annotation, factory.newDecorator(annotation), order);
    }

    /**
     * Returns a new decorator which decorates a {@link Service} by the specified
     * {@link Decorator}.
     */
    @SuppressWarnings("unchecked")
    private static Function<Service<HttpRequest, HttpResponse>,
            ? extends Service<HttpRequest, HttpResponse>> newDecorator(Decorator decorator) {
        return service -> service.decorate(getInstance(decorator, DecoratingServiceFunction.class));
    }

    /**
     * Returns a {@link Builder} which has the instances specified by the annotations of the
     * {@code annotationType}. The annotations of the specified {@code method} and {@code clazz} will be
     * collected respectively.
     * 获取被注解注解了的方法、或 类的实例对象。 注解在方法上，在类上的收集是独立进行的
     *
     */
    private static <T extends Annotation, R> Builder<R> getAnnotatedInstances(
            AnnotatedElement method, AnnotatedElement clazz, Class<T> annotationType, Class<R> resultType) {
        final Builder<R> builder = new Builder<>();
        Stream.concat(findAll(method, annotationType).stream(),
                      findAll(clazz, annotationType).stream())
              .forEach(annotation -> builder.add(getInstance(annotation, resultType)));
        return builder;
    }

    /**
     * Returns a cached instance of the specified {@link Class} which is specified in the given
     * {@link Annotation}.
     *
     * 返回一个被注解标记了的缓存着的类实例
     */
    static <T> T getInstance(Annotation annotation, Class<T> expectedType) {
        try {
            @SuppressWarnings("unchecked")
            final Class<? extends T> clazz = (Class<? extends T>) invokeValueMethod(annotation);
            return expectedType.cast(instanceCache.computeIfAbsent(clazz, type -> {
                try {
                    return getInstance0(clazz);
                } catch (Exception e) {
                    throw new IllegalStateException(
                            "A class specified in @" + annotation.getClass().getSimpleName() +
                            " annotation must have an accessible default constructor: " + clazz.getName(), e);
                }
            }));
        } catch (ClassCastException e) {
            throw new IllegalArgumentException(
                    "A class specified in @" + annotation.getClass().getSimpleName() +
                    " annotation cannot be cast to " + expectedType, e);
        }
    }

    /**
     * Returns a cached instance of the specified {@link Class}.
     * <br/>
     * 返回一个指定类的缓存实例
     */
    static <T> T getInstance(Class<T> clazz) {
        @SuppressWarnings("unchecked")
        final T casted = (T) instanceCache.computeIfAbsent(clazz, type -> {
            try {
                return getInstance0(clazz);
            } catch (Exception e) {
                throw new IllegalStateException("A class must have an accessible default constructor: " +
                                                clazz.getName(), e);
            }
        });
        return casted;
    }

    /**
     * 反射获取指定类型的实例对象
     * @param clazz
     * @param <T>
     * @return
     * @throws Exception
     */
    private static <T> T getInstance0(Class<? extends T> clazz) throws Exception {
        @SuppressWarnings("unchecked")
        final Constructor<? extends T> constructor =
                Iterables.getFirst(getConstructors(clazz, withParametersCount(0)), null);
        assert constructor != null : "No default constructor is found from " + clazz.getName();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    /**
     * Returns an object which is returned by {@code value()} method of the specified annotation {@code a}.
     * <br/>
     * 返回注解中属性value指定的值，如果传入的注解没有value方法(注解类型的属性)那么就断言异常
     */
    private static Object invokeValueMethod(Annotation a) {
        try {
            final Method method = Iterables.getFirst(getMethods(a.getClass(), withName("value")), null);
            assert method != null : "No 'value' method is found from " + a;
            return method.invoke(a);
        } catch (Exception e) {
            throw new IllegalStateException("An annotation @" + a.getClass().getSimpleName() +
                                            " must have a 'value' method", e);
        }
    }

    /**
     * Returns the description of the specified {@link AnnotatedElement}.
     * 返回被注解{@link Description}注解了的元素描述信息
     */
    @Nullable
    static String findDescription(AnnotatedElement annotatedElement) {
        requireNonNull(annotatedElement, "annotatedElement");
        final Optional<Description> description = findFirst(annotatedElement, Description.class);
        if (description.isPresent()) {
            final String value = description.get().value();
            if (DefaultValues.isSpecified(value)) {
                checkArgument(!value.isEmpty(), "value is empty");
                return value;
            }
        }
        return null;
    }

    private AnnotatedHttpServiceFactory() {}

    /**
     * An internal class to hold a decorator with its order.
     */
    @VisibleForTesting
    static final class DecoratorAndOrder {
        // Keep the specified annotation for testing purpose.
        private final Annotation annotation;
        private final Function<Service<HttpRequest, HttpResponse>,
                ? extends Service<HttpRequest, HttpResponse>> decorator;
        private final int order;

        private DecoratorAndOrder(Annotation annotation,
                                  Function<Service<HttpRequest, HttpResponse>,
                                          ? extends Service<HttpRequest, HttpResponse>> decorator,
                                  int order) {
            this.annotation = annotation;
            this.decorator = decorator;
            this.order = order;
        }

        Annotation annotation() {
            return annotation;
        }

        Function<Service<HttpRequest, HttpResponse>,
                ? extends Service<HttpRequest, HttpResponse>> decorator() {
            return decorator;
        }

        int order() {
            return order;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("annotation", annotation())
                              .add("decorator", decorator())
                              .add("order", order())
                              .toString();
        }
    }
}
