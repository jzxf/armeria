/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.grpc.interop;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;
import javax.net.ssl.SSLException;

import com.google.instrumentation.stats.StatsContextFactory;

import com.linecorp.armeria.common.http.HttpSessionProtocols;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.grpc.GrpcServiceBuilder;

import io.grpc.ServerServiceDefinition;
import io.grpc.internal.AbstractServerImplBuilder;
import io.grpc.internal.InternalServer;
import io.grpc.internal.NoopStatsContextFactory;

public class ArmeriaGrpcServerBuilder extends AbstractServerImplBuilder<ArmeriaGrpcServerBuilder> {

    private final com.linecorp.armeria.server.ServerBuilder armeriaServerBuilder;
    private final GrpcServiceBuilder grpcServiceBuilder;
    private final AtomicReference<ServiceRequestContext> ctxCapture;

    public ArmeriaGrpcServerBuilder(ServerBuilder armeriaServerBuilder,
                                    GrpcServiceBuilder grpcServiceBuilder,
                                    AtomicReference<ServiceRequestContext> ctxCapture) {
        this.armeriaServerBuilder = armeriaServerBuilder;
        this.grpcServiceBuilder = grpcServiceBuilder;
        this.ctxCapture = ctxCapture;
    }

    @Override
    public ArmeriaGrpcServerBuilder useTransportSecurity(File certChain, File privateKey) {
        try {
            armeriaServerBuilder.sslContext(HttpSessionProtocols.HTTPS, certChain, privateKey);
        } catch (SSLException e) {
            throw new IllegalArgumentException(e);
        }
        return this;
    }

    @Override
    protected ArmeriaGrpcServerBuilder statsContextFactory(StatsContextFactory statsFactory) {
        return super.statsContextFactory(NoopStatsContextFactory.INSTANCE);
    }

    @Override
    protected InternalServer buildTransportServer() {
        Object registryBuilder = getFieldByReflection("registryBuilder", this, AbstractServerImplBuilder.class);
        Map<String, ServerServiceDefinition> services = getFieldByReflection("services", registryBuilder, null);
        services.values().forEach(grpcServiceBuilder::addService);

        armeriaServerBuilder.serviceUnder("/", grpcServiceBuilder.build()
                                                                 .decorate((delegate, ctx, req) -> {
                                                                     ctxCapture.set(ctx);
                                                                     return delegate.serve(ctx, req);
                                                                 }));
        return new ArmeriaGrpcServer(armeriaServerBuilder.build());
    }

    @SuppressWarnings("unchecked")
    private <T> T getFieldByReflection(String name, Object instance, @Nullable Class<?> clazz) {
        try {
            Field field = (clazz != null ? clazz : instance.getClass()).getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(instance);
        } catch (Exception e) {
            throw new Error(e);
        }
    }
}
