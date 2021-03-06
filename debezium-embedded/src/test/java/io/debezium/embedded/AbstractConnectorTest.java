/*
 * Copyright Debezium Authors.
 * 
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.embedded;

import static org.junit.Assert.fail;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaAndValue;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.json.JsonConverter;
import org.apache.kafka.connect.json.JsonDeserializer;
import org.apache.kafka.connect.source.SourceConnector;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.kafka.connect.storage.FileOffsetBackingStore;
import org.junit.After;
import org.junit.Before;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static org.fest.assertions.Assertions.assertThat;

import io.debezium.config.Configuration;
import io.debezium.data.Envelope.FieldName;
import io.debezium.data.Envelope.Operation;
import io.debezium.embedded.EmbeddedEngine.CompletionCallback;
import io.debezium.function.BooleanConsumer;
import io.debezium.util.Testing;

/**
 * An abstract base class for unit testing {@link SourceConnector} implementations using the Debezium {@link EmbeddedEngine}
 * with local file storage.
 * <p>
 * To use this abstract class, simply create a test class that extends it, and add one or more test methods that
 * {@link #start(Class, Configuration) starts the connector} using your connector's custom configuration.
 * Then, your test methods can call {@link #consumeRecords(int, Consumer)} to consume the specified number
 * of records (the supplied function gives you a chance to do something with the record).
 * 
 * @author Randall Hauch
 */
public abstract class AbstractConnectorTest implements Testing {

    protected static final Path OFFSET_STORE_PATH = Testing.Files.createTestingPath("file-connector-offsets.txt").toAbsolutePath();

    private ExecutorService executor;
    private EmbeddedEngine engine;
    private BlockingQueue<SourceRecord> consumedLines;
    protected long pollTimeoutInMs = TimeUnit.SECONDS.toMillis(5);
    protected final Logger logger = LoggerFactory.getLogger(getClass());
    private CountDownLatch latch;
    private JsonConverter keyJsonConverter = new JsonConverter();
    private JsonConverter valueJsonConverter = new JsonConverter();
    private JsonDeserializer keyJsonDeserializer = new JsonDeserializer();
    private JsonDeserializer valueJsonDeserializer = new JsonDeserializer();

    @Before
    public final void initializeConnectorTestFramework() {
        keyJsonConverter = new JsonConverter();
        valueJsonConverter = new JsonConverter();
        keyJsonDeserializer = new JsonDeserializer();
        valueJsonDeserializer = new JsonDeserializer();
        Configuration converterConfig = Configuration.create().build();
        Configuration deserializerConfig = Configuration.create().build();
        keyJsonConverter.configure(converterConfig.asMap(), true);
        valueJsonConverter.configure(converterConfig.asMap(), false);
        keyJsonDeserializer.configure(deserializerConfig.asMap(), true);
        valueJsonDeserializer.configure(deserializerConfig.asMap(), false);

        resetBeforeEachTest();
        consumedLines = new ArrayBlockingQueue<>(getMaximumEnqueuedRecordCount());
        Testing.Files.delete(OFFSET_STORE_PATH);
    }

    /**
     * Stop the connector and block until the connector has completely stopped.
     */
    @After
    public final void stopConnector() {
        stopConnector(null);
    }

    /**
     * Stop the connector, and return whether the connector was successfully stopped.
     * 
     * @param callback the function that should be called with whether the connector was successfully stopped; may be null
     */
    public void stopConnector(BooleanConsumer callback) {
        try {
            // Try to stop the connector ...
            if (engine != null && engine.isRunning()) {
                engine.stop();
                try {
                    engine.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.interrupted();
                }
            }
            if (executor != null) {
                List<Runnable> neverRunTasks = executor.shutdownNow();
                assertThat(neverRunTasks).isEmpty();
                try {
                    while (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                        // wait for completion ...
                    }
                } catch (InterruptedException e) {
                    Thread.interrupted();
                }
            }
            if (engine != null && engine.isRunning()) {
                try {
                    while (!engine.await(5, TimeUnit.SECONDS)) {
                        // Wait for connector to stop completely ...
                    }
                } catch (InterruptedException e) {
                    Thread.interrupted();
                }
            }
            if (callback != null) callback.accept(engine != null ? engine.isRunning() : false);
        } finally {
            engine = null;
            executor = null;
        }
    }

    /**
     * Get the maximum number of messages that can be obtained from the connector and held in-memory before they are
     * consumed by test methods using {@link #consumeRecord()}, {@link #consumeRecords(int)}, or
     * {@link #consumeRecords(int, Consumer)}.
     * 
     * <p>
     * By default this method return {@code 100}.
     * 
     * @return the maximum number of records that can be enqueued
     */
    protected int getMaximumEnqueuedRecordCount() {
        return 100;
    }

    /**
     * Start the connector using the supplied connector configuration, where upon completion the status of the connector is
     * logged.
     * 
     * @param connectorClass the connector class; may not be null
     * @param connectorConfig the configuration for the connector; may not be null
     */
    protected void start(Class<? extends SourceConnector> connectorClass, Configuration connectorConfig) {
        start(connectorClass, connectorConfig, (success, msg, error) -> {
            if (success) {
                logger.info(msg);
            } else {
                logger.error(msg, error);
            }
        });
    }

    /**
     * Start the connector using the supplied connector configuration.
     * 
     * @param connectorClass the connector class; may not be null
     * @param connectorConfig the configuration for the connector; may not be null
     * @param callback the function that will be called when the engine fails to start the connector or when the connector
     *            stops running after completing successfully or due to an error; may be null
     */
    protected void start(Class<? extends SourceConnector> connectorClass, Configuration connectorConfig, CompletionCallback callback) {
        Configuration config = Configuration.copy(connectorConfig)
                                            .with(EmbeddedEngine.ENGINE_NAME, "testing-connector")
                                            .with(EmbeddedEngine.CONNECTOR_CLASS, connectorClass.getName())
                                            .with(FileOffsetBackingStore.OFFSET_STORAGE_FILE_FILENAME_CONFIG, OFFSET_STORE_PATH)
                                            .with(EmbeddedEngine.OFFSET_FLUSH_INTERVAL_MS, 0)
                                            .build();
        latch = new CountDownLatch(1);
        CompletionCallback wrapperCallback = (success, msg, error) -> {
            try {
                if (callback != null) callback.handle(success, msg, error);
            } finally {
                latch.countDown();
            }
        };

        // Create the connector ...
        engine = EmbeddedEngine.create()
                               .using(config)
                               .notifying((record) -> {
                                   try {
                                       consumedLines.put(record);
                                   } catch ( InterruptedException e ) {
                                       Thread.interrupted();
                                   }
                               })
                               .using(this.getClass().getClassLoader())
                               .using(wrapperCallback)
                               .build();

        // Submit the connector for asynchronous execution ...
        assertThat(executor).isNull();
        executor = Executors.newFixedThreadPool(1);
        executor.execute(engine);
    }

    /**
     * Set the maximum amount of time that the {@link #consumeRecord()}, {@link #consumeRecords(int)}, and
     * {@link #consumeRecords(int, Consumer)} methods block while waiting for each record before returning <code>null</code>.
     * 
     * @param timeout the timeout; must be positive
     * @param unit the time unit; may not be null
     */
    protected void setConsumeTimeout(long timeout, TimeUnit unit) {
        if (timeout < 0) throw new IllegalArgumentException("The timeout may not be negative");
        pollTimeoutInMs = unit.toMillis(timeout);
    }

    /**
     * Consume a single record from the connector.
     * 
     * @return the next record that was returned from the connector, or null if no such record has been produced by the connector
     * @throws InterruptedException if the thread was interrupted while waiting for a record to be returned
     */
    protected SourceRecord consumeRecord() throws InterruptedException {
        return consumedLines.poll(pollTimeoutInMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Try to consume the specified number of records from the connector, and return the actual number of records that were
     * consumed. Use this method when your test does not care what the records might contain.
     * 
     * @param numberOfRecords the number of records that should be consumed
     * @return the actual number of records that were consumed
     * @throws InterruptedException if the thread was interrupted while waiting for a record to be returned
     */
    protected int consumeRecords(int numberOfRecords) throws InterruptedException {
        return consumeRecords(numberOfRecords, null);
    }

    /**
     * Try to consume the specified number of records from the connector, calling the given function for each, and return the
     * actual number of records that were consumed.
     * 
     * @param numberOfRecords the number of records that should be consumed
     * @param recordConsumer the function that should be called with each consumed record
     * @return the actual number of records that were consumed
     * @throws InterruptedException if the thread was interrupted while waiting for a record to be returned
     */
    protected int consumeRecords(int numberOfRecords, Consumer<SourceRecord> recordConsumer) throws InterruptedException {
        int recordsConsumed = 0;
        while ( recordsConsumed < numberOfRecords ) {
            SourceRecord record = consumedLines.poll(pollTimeoutInMs, TimeUnit.MILLISECONDS);
            if (record != null) {
                ++recordsConsumed;
                if (recordConsumer != null) {
                    recordConsumer.accept(record);
                }
                if ( Testing.Debug.isEnabled() ) {
                    Testing.debug("Consumed record " + recordsConsumed + " / " + numberOfRecords + " (" + (numberOfRecords-recordsConsumed) + " more)");
                    debug(record);
                }
            }
        }
        return recordsConsumed;
    }
    
    protected SourceRecords consumeRecordsByTopic(int numRecords) throws InterruptedException {
        return consumeRecordsByTopic(numRecords, new SourceRecords());
    }
    
    protected SourceRecords consumeRecordsByTopic(int numRecords, SourceRecords records) throws InterruptedException {
        consumeRecords(numRecords,records::add);
        return records;
    }
    
    protected class SourceRecords {
        private final List<SourceRecord> records = new ArrayList<>();
        private final Map<String,List<SourceRecord>> recordsByTopic = new HashMap<>();
        private final Map<String,List<SourceRecord>> ddlRecordsByDbName = new HashMap<>();
        public void add( SourceRecord record ) {
            records.add(record);
            recordsByTopic.compute(record.topic(), (topicName,list)->{
                if ( list == null ) list = new ArrayList<SourceRecord>();
                list.add(record);
                return list;
            });
            if ( record.key() instanceof Struct ) {
                Struct key = (Struct)record.key();
                if ( key.schema().field("databaseName") != null) {
                    String dbName = key.getString("databaseName");
                    ddlRecordsByDbName.compute(dbName, (databaseName,list)->{
                        if ( list == null ) list = new ArrayList<SourceRecord>();
                        list.add(record);
                        return list;
                    });
                }
            }
        }
        public List<SourceRecord> ddlRecordsForDatabase( String dbName ) {
            return ddlRecordsByDbName.get(dbName);
        }
        public Set<String> databaseNames() {
            return ddlRecordsByDbName.keySet();
        }
        public List<SourceRecord> recordsForTopic( String topicName ) {
            return recordsByTopic.get(topicName);
        }
        public Set<String> topics() {
            return recordsByTopic.keySet();
        }
        public void forEachInTopic(String topic, Consumer<SourceRecord> consumer) {
            recordsForTopic(topic).forEach(consumer);
        }
        public void forEach( Consumer<SourceRecord> consumer) {
            records.forEach(consumer);
        }
        public void print() {
            Testing.print("" + topics().size() + " topics: " + topics());
            recordsByTopic.forEach((k,v)->{
                Testing.print(" - topic:'" + k + "'; # of events = " + v.size());
            });
            Testing.print("Records:" );
            records.forEach(record->AbstractConnectorTest.this.print(record));
        }
    }

    /**
     * Try to consume all of the messages that have already been returned by the connector.
     * 
     * @param recordConsumer the function that should be called with each consumed record
     * @return the number of records that were consumed
     */
    protected int consumeAvailableRecords(Consumer<SourceRecord> recordConsumer) {
        List<SourceRecord> records = new LinkedList<>();
        consumedLines.drainTo(records);
        if (recordConsumer != null) {
            records.forEach(recordConsumer);
        }
        return records.size();
    }
    
    /**
     * Wait for a maximum amount of time until the first record is available.
     * 
     * @param timeout the maximum amount of time to wait; must not be negative
     * @param unit the time unit for {@code timeout}
     * @return {@code true} if records are available, or {@code false} if the timeout occurred and no records are available
     */
    protected boolean waitForAvailableRecords(long timeout, TimeUnit unit) {
        assertThat(timeout).isGreaterThanOrEqualTo(0);
        long now = System.currentTimeMillis();
        long stop = now + unit.toMillis(timeout);
        while (System.currentTimeMillis() < stop) {
            if (!consumedLines.isEmpty()) break;
        }
        return consumedLines.isEmpty() ? false : true;
    }

    /**
     * Assert that the connector is currently running.
     */
    protected void assertConnectorIsRunning() {
        assertThat(engine.isRunning()).isTrue();
    }

    /**
     * Assert that the connector is NOT currently running.
     */
    protected void assertConnectorNotRunning() {
        assertThat(engine.isRunning()).isFalse();
    }

    /**
     * Assert that there are no records to consume.
     */
    protected void assertNoRecordsToConsume() {
        assertThat(consumedLines.isEmpty()).isTrue();
    }

    protected void assertKey(SourceRecord record, String pkField, int pk) {
        Struct key = (Struct) record.key();
        assertThat(key.get(pkField)).isEqualTo(pk);
    }

    protected void assertInsert(SourceRecord record, String pkField, int pk) {
        assertKey(record,pkField,pk);
        assertThat(record.key()).isNotNull();
        assertThat(record.keySchema()).isNotNull();
        assertThat(record.valueSchema()).isNotNull();
        Struct value = (Struct) record.value();
        assertThat(value).isNotNull();
        assertThat(value.get(FieldName.OPERATION)).isEqualTo(Operation.CREATE);
        assertThat(value.get(FieldName.AFTER)).isNotNull();
        assertThat(value.get(FieldName.BEFORE)).isNull();
    }

    protected void assertUpdate(SourceRecord record, String pkField, int pk) {
        assertKey(record,pkField,pk);
        assertThat(record.key()).isNotNull();
        assertThat(record.keySchema()).isNotNull();
        assertThat(record.valueSchema()).isNotNull();
        Struct value = (Struct) record.value();
        assertThat(value).isNotNull();
        assertThat(value.get(FieldName.OPERATION)).isEqualTo(Operation.UPDATE);
        assertThat(value.get(FieldName.AFTER)).isNotNull();
        //assertThat(value.get(FieldName.BEFORE)).isNull(); // may be null
    }

    protected void assertDelete(SourceRecord record, String pkField, int pk) {
        assertKey(record,pkField,pk);
        assertThat(record.key()).isNotNull();
        assertThat(record.keySchema()).isNotNull();
        assertThat(record.valueSchema()).isNotNull();
        Struct value = (Struct) record.value();
        assertThat(value).isNotNull();
        assertThat(value.get(FieldName.OPERATION)).isEqualTo(Operation.DELETE);
        assertThat(value.get(FieldName.BEFORE)).isNotNull();
        assertThat(value.get(FieldName.AFTER)).isNull();
    }

    protected void assertTombstone(SourceRecord record, String pkField, int pk) {
        assertKey(record,pkField,pk);
        assertTombstone(record);
    }

    protected void assertTombstone(SourceRecord record) {
        assertThat(record.key()).isNotNull();
        assertThat(record.keySchema()).isNotNull();
        assertThat(record.value()).isNull();
        assertThat(record.valueSchema()).isNull();
    }

    /**
     * Assert that the supplied {@link Struct} is {@link Struct#validate() valid} and its {@link Struct#schema() schema}
     * matches that of the supplied {@code schema}.
     * 
     * @param value the value with a schema; may not be null
     */
    protected void assertSchemaMatchesStruct(SchemaAndValue value) {
        Object val = value.value();
        assertThat(val).isInstanceOf(Struct.class);
        assertSchemaMatchesStruct((Struct) val, value.schema());
    }

    /**
     * Assert that the supplied {@link Struct} is {@link Struct#validate() valid} and its {@link Struct#schema() schema}
     * matches that of the supplied {@code schema}.
     * 
     * @param struct the {@link Struct} to validate; may not be null
     * @param schema the expected schema of the {@link Struct}; may not be null
     */
    protected void assertSchemaMatchesStruct(Struct struct, Schema schema) {
        // First validate the struct itself ...
        try {
            struct.validate();
        } catch (DataException e) {
            throw new AssertionError("The struct '" + struct + "' failed to validate", e);
        }

        Schema actualSchema = struct.schema();
        assertThat(actualSchema).isEqualTo(schema);
        assertFieldsInSchema(struct,schema);
    }

    private void assertFieldsInSchema(Struct struct, Schema schema ) {
        schema.fields().forEach(field->{
            Object val1 = struct.get(field);
            Object val2 = struct.get(field.name());
            assertThat(val1).isSameAs(val2);
            if ( val1 instanceof Struct ) {
                assertFieldsInSchema((Struct)val1,field.schema());
            }
        });
    }
    
    /**
     * Validate that a {@link SourceRecord}'s key and value can each be converted to a byte[] and then back to an equivalent
     * {@link SourceRecord}.
     * 
     * @param record the record to validate; may not be null
     */
    protected void validate(SourceRecord record) {
        print(record);

        JsonNode keyJson = null;
        JsonNode valueJson = null;
        SchemaAndValue keyWithSchema = null;
        SchemaAndValue valueWithSchema = null;
        try {
            // The key should never be null ...
            assertThat(record.key()).isNotNull();
            assertThat(record.keySchema()).isNotNull();
            
            // If the value is not null there must be a schema; otherwise, the schema should also be null ...
            if ( record.value() == null ) {
                assertThat(record.valueSchema()).isNull();
            } else {
                assertThat(record.valueSchema()).isNotNull();
            }
            
            // First serialize and deserialize the key ...
            byte[] keyBytes = keyJsonConverter.fromConnectData(record.topic(), record.keySchema(), record.key());
            keyJson = keyJsonDeserializer.deserialize(record.topic(), keyBytes);
            keyWithSchema = keyJsonConverter.toConnectData(record.topic(), keyBytes);
            assertThat(keyWithSchema.schema()).isEqualTo(record.keySchema());
            assertThat(keyWithSchema.value()).isEqualTo(record.key());
            assertSchemaMatchesStruct(keyWithSchema);

            // then the value ...
            byte[] valueBytes = valueJsonConverter.fromConnectData(record.topic(), record.valueSchema(), record.value());
            valueJson = valueJsonDeserializer.deserialize(record.topic(), valueBytes);
            valueWithSchema = valueJsonConverter.toConnectData(record.topic(), valueBytes);
            assertThat(valueWithSchema.schema()).isEqualTo(record.valueSchema());
            assertThat(valueWithSchema.value()).isEqualTo(record.value());
            assertSchemaMatchesStruct(valueWithSchema);
        } catch (Throwable t) {
            Testing.printError(t);
            Testing.print("Problem with message on topic '" + record.topic() + "':");
            if (keyJson != null) {
                Testing.print("valid key = " + prettyJson(keyJson));
            } else if (keyWithSchema != null) {
                Testing.print("valid key with schema = " + keyWithSchema);
            } else {
                Testing.print("invalid key");
            }
            if (valueJson != null) {
                Testing.print("valid value = " + prettyJson(valueJson));
            } else if (valueWithSchema != null) {
                Testing.print("valid value with schema = " + valueWithSchema);
            } else {
                Testing.print("invalid value");
            }
            if (t instanceof AssertionError) throw t;
            fail(t.getMessage());
        }
    }

    protected String printToString(SourceRecord record) {
        StringBuilder sb = new StringBuilder("SourceRecord{");
        sb.append("sourcePartition=").append(record.sourcePartition());
        sb.append(", sourceOffset=").append(record.sourceOffset());
        sb.append(", topic=").append(record.topic());
        sb.append(", kafkaPartition=").append(record.kafkaPartition());
        sb.append(", key=");
        append(record.key(), sb);
        sb.append(", value=");
        append(record.value(), sb);
        sb.append("}");
        return sb.toString();
    }

    protected void print(SourceRecord record) {
        Testing.print(printToString(record));
    }

    protected void debug(SourceRecord record) {
        Testing.debug(printToString(record));
    }

    protected void printJson(SourceRecord record) {
        JsonNode keyJson = null;
        JsonNode valueJson = null;
        try {
            // First serialize and deserialize the key ...
            byte[] keyBytes = keyJsonConverter.fromConnectData(record.topic(), record.keySchema(), record.key());
            keyJson = keyJsonDeserializer.deserialize(record.topic(), keyBytes);
            // then the value ...
            byte[] valueBytes = valueJsonConverter.fromConnectData(record.topic(), record.valueSchema(), record.value());
            valueJson = valueJsonDeserializer.deserialize(record.topic(), valueBytes);
            // And finally get ready to print it ...
            JsonNodeFactory nodeFactory = new JsonNodeFactory(false);
            ObjectNode message = nodeFactory.objectNode();
            message.set("key", keyJson);
            message.set("value", valueJson);
            Testing.print("Message on topic '" + record.topic() + "':");
            Testing.print(prettyJson(message));
        } catch (Throwable t) {
            Testing.printError(t);
            Testing.print("Problem with message on topic '" + record.topic() + "':");
            if (keyJson != null) {
                Testing.print("valid key = " + prettyJson(keyJson));
            } else {
                Testing.print("invalid key");
            }
            if (valueJson != null) {
                Testing.print("valid value = " + prettyJson(valueJson));
            } else {
                Testing.print("invalid value");
            }
            fail(t.getMessage());
        }
    }

    protected String prettyJson(JsonNode json) {
        try {
            return new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(json);
        } catch (Throwable t) {
            Testing.printError(t);
            fail(t.getMessage());
            assert false : "Will not get here";
            return null;
        }
    }

    protected void append(Object obj, StringBuilder sb) {
        if (obj == null) {
            sb.append("null");
        } else if (obj instanceof Schema) {
            Schema schema = (Schema) obj;
            sb.append('{');
            sb.append("name=").append(schema.name());
            sb.append(", type=").append(schema.type());
            sb.append(", optional=").append(schema.isOptional());
            sb.append(", fields=");
            boolean first = true;
            for (Field field : schema.fields()) {
                if (first)
                    first = false;
                else
                    sb.append(", ");
                sb.append("name=").append(field.name());
                sb.append(", index=").append(field.index());
                sb.append(", schema=");
                append(field.schema(), sb);
            }
            sb.append('}');
        } else if (obj instanceof Struct) {
            Struct s = (Struct) obj;
            sb.append('{');
            boolean first = true;
            for (Field field : s.schema().fields()) {
                if (first)
                    first = false;
                else
                    sb.append(", ");
                sb.append(field.name()).append('=');
                append(s.get(field), sb);
            }
            sb.append('}');
        } else if (obj instanceof Map<?, ?>) {
            Map<?, ?> map = (Map<?, ?>) obj;
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (first)
                    first = false;
                else
                    sb.append(", ");
                append(entry.getKey(), sb);
                sb.append('=');
                append(entry.getValue(), sb);
            }
            sb.append('}');
        } else if (obj instanceof List<?>) {
            List<?> list = (List<?>) obj;
            sb.append('[');
            boolean first = true;
            for (Object value : list) {
                if (first)
                    first = false;
                else
                    sb.append(", ");
                append(value, sb);
            }
            sb.append(']');
        } else if (obj instanceof String) {
            sb.append('"').append(obj.toString()).append('"');
        } else {
            sb.append(obj.toString());
        }
    }
}
