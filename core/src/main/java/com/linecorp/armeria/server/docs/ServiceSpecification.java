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

package com.linecorp.armeria.server.docs;

import static com.google.common.collect.ImmutableSortedSet.toImmutableSortedSet;
import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Streams;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.server.Service;

/**
 * The specification of one or more {@link Service}s that provides their {@link ServiceInfo}s and
 * {@link NamedTypeInfo}s.
 * 一个或多个的Service的使用说明书，细致的来说，其提供了它们各自的{@link ServiceInfo}信息与{@link NamedTypeInfo}信息
 *
 * 哪个Service
 * Service下有哪些方法
 * 方法包含哪些参数，方法对应的请求路径，方法的返回类型
 * 参数类型，参数是否必需等等信息
 *
 */
public final class ServiceSpecification {

    private static final ServiceSpecification emptyServiceSpecification =
            new ServiceSpecification(ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                                     ImmutableList.of(), ImmutableList.of());

    /**
     * Merges the specified {@link ServiceSpecification}s into one.
     * <p>合并指定的{@link ServiceSpecification}集合，合并成一个</p>
     */
    public static ServiceSpecification merge(Iterable<ServiceSpecification> specs) {
        return new ServiceSpecification(
                Streams.stream(specs).flatMap(s -> s.services().stream())::iterator,
                Streams.stream(specs).flatMap(s -> s.enums().stream())::iterator,
                Streams.stream(specs).flatMap(s -> s.structs().stream())::iterator,
                Streams.stream(specs).flatMap(s -> s.exceptions().stream())::iterator);
    }

    /**
     * Generates a new {@link ServiceSpecification} from the specified {@link ServiceInfo}s and
     * the factory {@link Function} that creates {@link NamedTypeInfo}s for the enum, struct or exception types
     * referred by the specified {@link ServiceInfo}s.
     * <br/>
     * 从指定的{@link ServiceInfo}集合和一个参考于{@link ServiceInfo}s内指定的类型的创建{@link NamedTypeInfo}s的工厂，生成一个服务说明书实例即({@link ServiceSpecification}).
     *
     */
    public static ServiceSpecification generate(
            Iterable<ServiceInfo> services,
            Function<TypeSignature, ? extends NamedTypeInfo> namedTypeInfoFactory) {
        if (Iterables.isEmpty(services)) {
            return emptyServiceSpecification;
        }

        // Collect all named types referred by the services.
        final Set<TypeSignature> namedTypes =
                Streams.stream(services)
                       .flatMap(s -> s.findNamedTypes().stream())
                       .collect(toImmutableSortedSet(comparing(TypeSignature::name)));

        final Map<String, EnumInfo> enums = new HashMap<>();
        final Map<String, StructInfo> structs = new HashMap<>();
        final Map<String, ExceptionInfo> exceptions = new HashMap<>();

        generateNamedTypeInfos(namedTypeInfoFactory, enums, structs, exceptions, namedTypes);

        return new ServiceSpecification(services, enums.values(), structs.values(), exceptions.values());
    }

    private static void generateNamedTypeInfos(
            Function<TypeSignature, ? extends NamedTypeInfo> namedTypeInfoFactory,
            Map<String, EnumInfo> enums, Map<String, StructInfo> structs,
            Map<String, ExceptionInfo> exceptions, Set<TypeSignature> namedTypes) {

        namedTypes.forEach(type -> {
            final String typeName = type.name();
            if (enums.containsKey(typeName) ||
                structs.containsKey(typeName) ||
                exceptions.containsKey(typeName)) {
                return;
            }

            final NamedTypeInfo newInfo = namedTypeInfoFactory.apply(type);
            if (newInfo instanceof EnumInfo) {
                enums.put(newInfo.name(), (EnumInfo) newInfo);
            } else if (newInfo instanceof StructInfo) {
                structs.put(newInfo.name(), (StructInfo) newInfo);
            } else if (newInfo instanceof ExceptionInfo) {
                exceptions.put(newInfo.name(), (ExceptionInfo) newInfo);
            } else {
                throw new Error(); // Should never reach here.
            }

            generateNamedTypeInfos(namedTypeInfoFactory, enums, structs, exceptions, newInfo.findNamedTypes());
        });
    }

    private final Set<ServiceInfo> services;
    private final Set<EnumInfo> enums;
    private final Set<StructInfo> structs;
    private final Set<ExceptionInfo> exceptions;
    private final List<HttpHeaders> exampleHttpHeaders;

    /**
     * Creates a new instance.
     */
    public ServiceSpecification(Iterable<ServiceInfo> services,
                                Iterable<EnumInfo> enums,
                                Iterable<StructInfo> structs,
                                Iterable<ExceptionInfo> exceptions) {
        this(services, enums, structs, exceptions, ImmutableList.of());
    }

    /**
     * Creates a new instance.
     */
    public ServiceSpecification(Iterable<ServiceInfo> services,
                                Iterable<EnumInfo> enums,
                                Iterable<StructInfo> structs,
                                Iterable<ExceptionInfo> exceptions,
                                Iterable<HttpHeaders> exampleHttpHeaders) {

        this.services = Streams.stream(requireNonNull(services, "services"))
                               .collect(toImmutableSortedSet(comparing(ServiceInfo::name)));
        this.enums = collectNamedTypeInfo(enums, "enums");
        this.structs = collectNamedTypeInfo(structs, "structs");
        this.exceptions = collectNamedTypeInfo(exceptions, "exceptions");
        this.exampleHttpHeaders = ImmutableList.copyOf(requireNonNull(exampleHttpHeaders,
                                                                      "exampleHttpHeaders"));
    }

    private static <T extends NamedTypeInfo> Set<T> collectNamedTypeInfo(Iterable<T> values, String name) {
        return Streams.stream(requireNonNull(values, name))
                      .collect(toImmutableSortedSet(comparing(NamedTypeInfo::name)));
    }

    /**
     * Returns the metadata about the services in this specification.
     */
    @JsonProperty
    public Set<ServiceInfo> services() {
        return services;
    }

    /**
     * Returns the metadata about the enums related with the services in this specification.
     */
    @JsonProperty
    public Set<EnumInfo> enums() {
        return enums;
    }

    /**
     * Returns the metadata about the structs related with the services in this specification.
     */
    @JsonProperty
    public Set<StructInfo> structs() {
        return structs;
    }

    /**
     * Returns the metadata about the exceptions related with the services in this specification.
     */
    @JsonProperty
    public Set<ExceptionInfo> exceptions() {
        return exceptions;
    }

    /**
     * Returns the example HTTP headers of the services in this specification.
     */
    @JsonProperty
    public List<HttpHeaders> exampleHttpHeaders() {
        return exampleHttpHeaders;
    }
}
