/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.streams.state.internals;

import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.MockTime;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.StreamsConfig.InternalConfig;
import org.apache.kafka.streams.errors.InvalidStateStoreException;
import org.apache.kafka.streams.processor.StateStore;
import org.apache.kafka.streams.processor.StateStoreContext;
import org.apache.kafka.streams.processor.TaskId;
import org.apache.kafka.streams.processor.internals.ProcessorContextImpl;
import org.apache.kafka.streams.processor.internals.metrics.StreamsMetricsImpl;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.apache.kafka.streams.state.ReadOnlySessionStore;
import org.apache.kafka.streams.state.ReadOnlyWindowStore;
import org.apache.kafka.streams.state.Stores;
import org.apache.kafka.streams.state.TimestampedKeyValueStore;
import org.apache.kafka.streams.state.TimestampedWindowStore;
import org.apache.kafka.streams.state.ValueAndTimestamp;
import org.apache.kafka.test.NoOpReadOnlyStore;
import org.junit.Before;
import org.junit.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.niceMock;
import static org.easymock.EasyMock.replay;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;


public class GlobalStateStoreProviderTest {
    private final Map<String, StateStore> stores = new HashMap<>();

    @Before
    public void before() {
        stores.put(
            "kv-store",
            Stores.keyValueStoreBuilder(
                Stores.inMemoryKeyValueStore("kv-store"),
                Serdes.String(),
                Serdes.String()).build());
        stores.put(
            "ts-kv-store",
            Stores.timestampedKeyValueStoreBuilder(
                Stores.inMemoryKeyValueStore("ts-kv-store"),
                Serdes.String(),
                Serdes.String()).build());
        stores.put(
            "w-store",
            Stores.windowStoreBuilder(
                Stores.inMemoryWindowStore(
                    "w-store",
                    Duration.ofMillis(10L),
                    Duration.ofMillis(2L),
                    false),
                Serdes.String(),
                Serdes.String()).build());
        stores.put(
            "ts-w-store",
            Stores.timestampedWindowStoreBuilder(
                Stores.inMemoryWindowStore(
                    "ts-w-store",
                    Duration.ofMillis(10L),
                    Duration.ofMillis(2L),
                    false),
                Serdes.String(),
                Serdes.String()).build());
        stores.put(
            "s-store",
            Stores.sessionStoreBuilder(
                Stores.inMemorySessionStore(
                    "s-store",
                    Duration.ofMillis(10L)),
                Serdes.String(),
                Serdes.String()).build());

        final ProcessorContextImpl mockContext = niceMock(ProcessorContextImpl.class);
        expect(mockContext.applicationId()).andStubReturn("appId");
        expect(mockContext.metrics())
            .andStubReturn(
                new StreamsMetricsImpl(new Metrics(), "threadName", StreamsConfig.METRICS_LATEST, new MockTime())
            );
        expect(mockContext.taskId()).andStubReturn(new TaskId(0, 0));
        expect(mockContext.recordCollector()).andStubReturn(null);
        final Map<String, Object> configValues = new HashMap<>();
        configValues.put(InternalConfig.IQ_CONSISTENCY_OFFSET_VECTOR_ENABLED, false);
        expect(mockContext.appConfigs()).andStubReturn(configValues);
        expectSerdes(mockContext);
        replay(mockContext);
        for (final StateStore store : stores.values()) {
            store.init((StateStoreContext) mockContext, null);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void expectSerdes(final ProcessorContextImpl context) {
        expect(context.keySerde()).andStubReturn((Serde) Serdes.String());
        expect(context.valueSerde()).andStubReturn((Serde) Serdes.Long());
    }

    @Test
    public void shouldReturnSingleItemListIfStoreExists() {
        final GlobalStateStoreProvider provider =
            new GlobalStateStoreProvider(Collections.singletonMap("global", new NoOpReadOnlyStore<>()));
        final List<ReadOnlyKeyValueStore<Object, Object>> stores =
            provider.stores("global", QueryableStoreTypes.keyValueStore());
        assertEquals(stores.size(), 1);
    }

    @Test
    public void shouldReturnEmptyItemListIfStoreDoesntExist() {
        final GlobalStateStoreProvider provider = new GlobalStateStoreProvider(Collections.emptyMap());
        final List<ReadOnlyKeyValueStore<Object, Object>> stores =
            provider.stores("global", QueryableStoreTypes.keyValueStore());
        assertTrue(stores.isEmpty());
    }

    @Test
    public void shouldThrowExceptionIfStoreIsntOpen() {
        final NoOpReadOnlyStore<Object, Object> store = new NoOpReadOnlyStore<>();
        store.close();
        final GlobalStateStoreProvider provider =
            new GlobalStateStoreProvider(Collections.singletonMap("global", store));
        assertThrows(InvalidStateStoreException.class, () -> provider.stores("global",
            QueryableStoreTypes.keyValueStore()));
    }

    @Test
    public void shouldReturnKeyValueStore() {
        final GlobalStateStoreProvider provider = new GlobalStateStoreProvider(stores);
        final List<ReadOnlyKeyValueStore<String, String>> stores =
            provider.stores("kv-store", QueryableStoreTypes.keyValueStore());
        assertEquals(1, stores.size());
        for (final ReadOnlyKeyValueStore<String, String> store : stores) {
            assertThat(store, instanceOf(ReadOnlyKeyValueStore.class));
            assertThat(store, not(instanceOf(TimestampedKeyValueStore.class)));
        }
    }

    @Test
    public void shouldReturnTimestampedKeyValueStore() {
        final GlobalStateStoreProvider provider = new GlobalStateStoreProvider(stores);
        final List<ReadOnlyKeyValueStore<String, ValueAndTimestamp<String>>> stores =
            provider.stores("ts-kv-store", QueryableStoreTypes.timestampedKeyValueStore());
        assertEquals(1, stores.size());
        for (final ReadOnlyKeyValueStore<String, ValueAndTimestamp<String>> store : stores) {
            assertThat(store, instanceOf(ReadOnlyKeyValueStore.class));
            assertThat(store, instanceOf(TimestampedKeyValueStore.class));
        }
    }

    @Test
    public void shouldNotReturnKeyValueStoreAsTimestampedStore() {
        final GlobalStateStoreProvider provider = new GlobalStateStoreProvider(stores);
        final List<ReadOnlyKeyValueStore<String, ValueAndTimestamp<String>>> stores =
            provider.stores("kv-store", QueryableStoreTypes.timestampedKeyValueStore());
        assertEquals(0, stores.size());
    }

    @Test
    public void shouldReturnTimestampedKeyValueStoreAsKeyValueStore() {
        final GlobalStateStoreProvider provider = new GlobalStateStoreProvider(stores);
        final List<ReadOnlyKeyValueStore<String, ValueAndTimestamp<String>>> stores =
            provider.stores("ts-kv-store", QueryableStoreTypes.keyValueStore());
        assertEquals(1, stores.size());
        for (final ReadOnlyKeyValueStore<String, ValueAndTimestamp<String>> store : stores) {
            assertThat(store, instanceOf(ReadOnlyKeyValueStore.class));
            assertThat(store, not(instanceOf(TimestampedKeyValueStore.class)));
        }
    }

    @Test
    public void shouldReturnWindowStore() {
        final GlobalStateStoreProvider provider = new GlobalStateStoreProvider(stores);
        final List<ReadOnlyWindowStore<String, String>> stores =
                provider.stores("w-store", QueryableStoreTypes.windowStore());
        assertEquals(1, stores.size());
        for (final ReadOnlyWindowStore<String, String> store : stores) {
            assertThat(store, instanceOf(ReadOnlyWindowStore.class));
            assertThat(store, not(instanceOf(TimestampedWindowStore.class)));
        }
    }

    @Test
    public void shouldNotReturnWindowStoreAsTimestampedStore() {
        final GlobalStateStoreProvider provider = new GlobalStateStoreProvider(stores);
        final List<ReadOnlyWindowStore<String, ValueAndTimestamp<String>>> stores =
                provider.stores("w-store", QueryableStoreTypes.timestampedWindowStore());
        assertEquals(0, stores.size());
    }

    @Test
    public void shouldReturnTimestampedWindowStoreAsWindowStore() {
        final GlobalStateStoreProvider provider = new GlobalStateStoreProvider(stores);
        final List<ReadOnlyWindowStore<String, ValueAndTimestamp<String>>> stores =
            provider.stores("ts-w-store", QueryableStoreTypes.windowStore());
        assertEquals(1, stores.size());
        for (final ReadOnlyWindowStore<String, ValueAndTimestamp<String>> store : stores) {
            assertThat(store, instanceOf(ReadOnlyWindowStore.class));
            assertThat(store, not(instanceOf(TimestampedWindowStore.class)));
        }
    }

    @Test
    public void shouldReturnSessionStore() {
        final GlobalStateStoreProvider provider = new GlobalStateStoreProvider(stores);
        final List<ReadOnlySessionStore<String, String>> stores =
                provider.stores("s-store", QueryableStoreTypes.sessionStore());
        assertEquals(1, stores.size());
        for (final ReadOnlySessionStore<String, String> store : stores) {
            assertThat(store, instanceOf(ReadOnlySessionStore.class));
        }
    }
}