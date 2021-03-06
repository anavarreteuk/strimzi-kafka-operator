/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.common.operator.resource;

import io.fabric8.kubernetes.api.model.Doneable;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.internal.readiness.Readiness;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.matches;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public abstract class AbtractReadyResourceOperatorTest<C extends KubernetesClient, T extends HasMetadata,
        L extends KubernetesResourceList, D extends Doneable<T>, R extends Resource<T, D>> extends AbstractResourceOperatorTest<C, T, L, D, R> {

    @Override
    protected abstract AbstractReadyResourceOperator<C, T, L, D, R> createResourceOperations(Vertx vertx, C mockClient);

    @Test
    public void waitUntilReadyWhenDoesNotExist(VertxTestContext context) {
        T resource = resource();
        Resource mockResource = mock(resourceType());
        when(mockResource.get()).thenReturn(null);

        NonNamespaceOperation mockNameable = mock(NonNamespaceOperation.class);
        when(mockNameable.withName(matches(resource.getMetadata().getName()))).thenReturn(mockResource);

        MixedOperation mockCms = mock(MixedOperation.class);
        when(mockCms.inNamespace(matches(resource.getMetadata().getNamespace()))).thenReturn(mockNameable);

        C mockClient = mock(clientType());
        mocker(mockClient, mockCms);

        AbstractReadyResourceOperator<C, T, L, D, R> op = createResourceOperations(vertx, mockClient);
        Checkpoint async = context.checkpoint();
        Future<Void> fut = op.readiness(NAMESPACE, RESOURCE_NAME, 20, 100);
        fut.setHandler(ar -> {
            assertThat(ar.failed(), is(true));
            assertThat(ar.cause(), instanceOf(TimeoutException.class));
            verify(mockResource, atLeastOnce()).get();
            verify(mockResource, never()).isReady();
            async.flag();
        });

    }

    @Test
    public void waitUntilReadyExistenceCheckThrows(VertxTestContext context) {
        T resource = resource();
        RuntimeException ex = new RuntimeException("This is a test exception");

        Resource mockResource = mock(resourceType());
        when(mockResource.get()).thenThrow(ex);

        NonNamespaceOperation mockNameable = mock(NonNamespaceOperation.class);
        when(mockNameable.withName(matches(resource.getMetadata().getName()))).thenReturn(mockResource);

        MixedOperation mockCms = mock(MixedOperation.class);
        when(mockCms.inNamespace(matches(resource.getMetadata().getNamespace()))).thenReturn(mockNameable);

        C mockClient = mock(clientType());
        mocker(mockClient, mockCms);

        AbstractReadyResourceOperator<C, T, L, D, R> op = createResourceOperations(vertx, mockClient);

        Checkpoint async = context.checkpoint();
        op.readiness(NAMESPACE, RESOURCE_NAME, 20, 100).setHandler(ar -> {
            assertThat(ar.failed(), is(true));
            assertThat(ar.cause(), instanceOf(TimeoutException.class));
            verify(mockResource, never()).isReady();
            async.flag();
        });
    }

    @Test
    public void waitUntilReadySuccessfulImmediately(VertxTestContext context) {
        waitUntilReadySuccessful(context, 0);
    }

    @Test
    public void waitUntilReadySuccessfulAfterOneCall(VertxTestContext context) {
        waitUntilReadySuccessful(context, 1);
    }

    @Test
    public void waitUntilReadySuccessfulAfterTwoCalls(VertxTestContext context) {
        waitUntilReadySuccessful(context, 2);
    }

    public void waitUntilReadySuccessful(VertxTestContext context, int unreadyCount) {
        T resource = resource();
        Resource mockResource = mock(resourceType());
        when(mockResource.get()).thenReturn(resource);
        AtomicInteger count = new AtomicInteger();
        when(mockResource.isReady()).then(invocation -> {
            int cnt = count.getAndIncrement();
            if (cnt < unreadyCount) {
                return Boolean.FALSE;
            } else if (cnt == unreadyCount) {
                return Boolean.TRUE;
            } else {
                context.failNow(new Throwable("The resource has already been ready once!"));
            }
            throw new RuntimeException();
        });

        NonNamespaceOperation mockNameable = mock(NonNamespaceOperation.class);
        when(mockNameable.withName(matches(resource.getMetadata().getName()))).thenReturn(mockResource);

        MixedOperation mockCms = mock(MixedOperation.class);
        when(mockCms.inNamespace(matches(resource.getMetadata().getNamespace()))).thenReturn(mockNameable);

        C mockClient = mock(clientType());
        mocker(mockClient, mockCms);

        AbstractReadyResourceOperator<C, T, L, D, R> op = createResourceOperations(vertx, mockClient);

        Checkpoint async = context.checkpoint();
        op.readiness(NAMESPACE, RESOURCE_NAME, 20, 5_000).setHandler(ar -> {
            assertThat(ar.succeeded(), is(true));
            verify(mockResource, times(Readiness.isReadinessApplicable(resource.getClass()) ? unreadyCount + 1 : 1)).get();

            if (Readiness.isReadinessApplicable(resource.getClass())) {
                verify(mockResource, times(unreadyCount + 1)).isReady();
            }
            async.flag();
        });
    }

    @Test
    public void waitUntilReadyUnsuccessful(VertxTestContext context) {
        T resource = resource();

        if (!Readiness.isReadinessApplicable(resource.getClass()))  {
            return;
        }

        Resource mockResource = mock(resourceType());
        when(mockResource.get()).thenReturn(resource);
        when(mockResource.isReady()).thenReturn(Boolean.FALSE);

        NonNamespaceOperation mockNameable = mock(NonNamespaceOperation.class);
        when(mockNameable.withName(matches(resource.getMetadata().getName()))).thenReturn(mockResource);

        MixedOperation mockCms = mock(MixedOperation.class);
        when(mockCms.inNamespace(matches(resource.getMetadata().getNamespace()))).thenReturn(mockNameable);

        C mockClient = mock(clientType());
        mocker(mockClient, mockCms);

        AbstractReadyResourceOperator<C, T, L, D, R> op = createResourceOperations(vertx, mockClient);

        Checkpoint async = context.checkpoint();
        op.readiness(NAMESPACE, RESOURCE_NAME, 20, 100).setHandler(ar -> {
            assertThat(ar.failed(), is(true));
            assertThat(ar.cause(), instanceOf(TimeoutException.class));
            verify(mockResource, atLeastOnce()).get();
            verify(mockResource, atLeastOnce()).isReady();
            async.flag();
        });
    }

    @Test
    public void waitUntilReadyThrows(VertxTestContext context) {
        T resource = resource();

        if (!Readiness.isReadinessApplicable(resource.getClass()))  {
            return;
        }

        RuntimeException ex = new RuntimeException("This is a test exception");

        Resource mockResource = mock(resourceType());
        when(mockResource.get()).thenReturn(resource());
        when(mockResource.isReady()).thenThrow(ex);

        NonNamespaceOperation mockNameable = mock(NonNamespaceOperation.class);
        when(mockNameable.withName(matches(resource.getMetadata().getName()))).thenReturn(mockResource);

        MixedOperation mockCms = mock(MixedOperation.class);
        when(mockCms.inNamespace(matches(resource.getMetadata().getNamespace()))).thenReturn(mockNameable);

        C mockClient = mock(clientType());
        mocker(mockClient, mockCms);

        AbstractReadyResourceOperator<C, T, L, D, R> op = createResourceOperations(vertx, mockClient);

        Checkpoint async = context.checkpoint();
        op.readiness(NAMESPACE, RESOURCE_NAME, 20, 100).setHandler(ar -> {
            assertThat(ar.failed(), is(true));
            assertThat(ar.cause(), instanceOf(TimeoutException.class));
            async.flag();
        });
    }
}
