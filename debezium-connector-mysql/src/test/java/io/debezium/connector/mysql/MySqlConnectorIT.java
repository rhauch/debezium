/*
 * Copyright Debezium Authors.
 * 
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.mysql;

import static org.junit.Assert.fail;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;

import io.debezium.config.Configuration;
import io.debezium.embedded.AbstractConnectorTest;
import io.debezium.jdbc.JdbcConnection;
import io.debezium.relational.history.FileDatabaseHistory;
import io.debezium.util.Testing;

/**
 * @author Randall Hauch
 */
public class MySqlConnectorIT extends AbstractConnectorTest {

    private static final Path DB_HISTORY_PATH = Testing.Files.createTestingPath("file-db-history.txt").toAbsolutePath();

    private Configuration config;

    @Before
    public void beforeEach() {
        stopConnector();
        initializeConnectorTestFramework();
        Testing.Files.delete(DB_HISTORY_PATH);
    }

    @After
    public void afterEach() {
        stopConnector();
        Testing.Files.delete(DB_HISTORY_PATH);
    }

    /**
     * Verifies that the connector doesn't run with an invalid configuration. This does not actually connect to the MySQL server.
     */
    @Test
    public void shouldNotStartWithInvalidConfiguration() {
        config = Configuration.create()
                              .with(MySqlConnectorConfig.DATABASE_HISTORY, FileDatabaseHistory.class)
                              .with(FileDatabaseHistory.FILE_PATH, DB_HISTORY_PATH)
                              .build();

        // we expect the engine will log at least one error, so preface it ...
        logger.info("Attempting to start the connector with an INVALID configuration, so MULTIPLE error messages & one exceptions will appear in the log");
        start(MySqlConnector.class, config, (success, msg, error) -> {
            assertThat(success).isFalse();
            assertThat(error).isNotNull();
        });
        assertConnectorNotRunning();
    }

    @Test
    public void shouldConsumeAllEventsFromDatabase() throws SQLException, InterruptedException {
        // Use the DB configuration to define the connector's configuration ...
        config = Configuration.create()
                              .with(MySqlConnectorConfig.HOSTNAME, System.getProperty("database.hostname"))
                              .with(MySqlConnectorConfig.PORT, System.getProperty("database.port"))
                              .with(MySqlConnectorConfig.USER, "replicator")
                              .with(MySqlConnectorConfig.PASSWORD, "replpass")
                              .with(MySqlConnectorConfig.SERVER_ID, 18765)
                              .with(MySqlConnectorConfig.SERVER_NAME, "kafka-connect")
                              .with(MySqlConnectorConfig.INITIAL_BINLOG_FILENAME, "mysql-bin.000001")
                              .with(MySqlConnectorConfig.DATABASE_WHITELIST, "connector_test")
                              .with(MySqlConnectorConfig.DATABASE_HISTORY, FileDatabaseHistory.class)
                              .with(MySqlConnectorConfig.INCLUDE_SCHEMA_CHANGES, true)
                              .with(FileDatabaseHistory.FILE_PATH, DB_HISTORY_PATH)
                              .build();
        // Start the connector ...
        start(MySqlConnector.class, config);

        // ---------------------------------------------------------------------------------------------------------------
        // Consume all of the events due to startup and initialization of the database
        // ---------------------------------------------------------------------------------------------------------------
        SourceRecords records = consumeRecordsByTopic(6+9+9+4+5);
        assertThat(records.recordsForTopic("kafka-connect").size()).isEqualTo(6);
        assertThat(records.recordsForTopic("kafka-connect.connector_test.products").size()).isEqualTo(9);
        assertThat(records.recordsForTopic("kafka-connect.connector_test.products_on_hand").size()).isEqualTo(9);
        assertThat(records.recordsForTopic("kafka-connect.connector_test.customers").size()).isEqualTo(4);
        assertThat(records.recordsForTopic("kafka-connect.connector_test.orders").size()).isEqualTo(5);
        assertThat(records.topics().size()).isEqualTo(5);
        assertThat(records.databaseNames().size()).isEqualTo(1);
        assertThat(records.ddlRecordsForDatabase("connector_test").size()).isEqualTo(6);
        assertThat(records.ddlRecordsForDatabase("readbinlog_test")).isNull();
        records.ddlRecordsForDatabase("connector_test").forEach(this::print);

        // Check that all records are valid, can be serialized and deserialized ...
        records.forEach(this::validate);
        
        // ---------------------------------------------------------------------------------------------------------------
        // Stopping the connector does not lose events recorded when connector is not running
        // ---------------------------------------------------------------------------------------------------------------

        // Make sure there are no more events and then stop the connector ...
        waitForAvailableRecords(3, TimeUnit.SECONDS);
        int totalConsumed = consumeAvailableRecords(this::print);
        assertThat(totalConsumed).isEqualTo(0);
        stopConnector();
        
        // Make some changes to data only while the connector is stopped ...
        try (MySQLConnection db = MySQLConnection.forTestDatabase("connector_test");) {
            try (JdbcConnection connection = db.connect()) {
                connection.query("SELECT * FROM products", rs -> {
                    if (Testing.Print.isEnabled()) connection.print(rs);
                });
                connection.execute("INSERT INTO products VALUES (default,'robot','Toy robot',1.304);");
                connection.query("SELECT * FROM products", rs -> {
                    if (Testing.Print.isEnabled()) connection.print(rs);
                });
            }
        }

        // Restart the connector and read the insert record ...
        start(MySqlConnector.class, config);
        records = consumeRecordsByTopic(1);
        assertThat(records.recordsForTopic("kafka-connect.connector_test.products").size()).isEqualTo(1);
        assertThat(records.topics().size()).isEqualTo(1);
        
        // ---------------------------------------------------------------------------------------------------------------
        // Simple INSERT
        // ---------------------------------------------------------------------------------------------------------------
        try (MySQLConnection db = MySQLConnection.forTestDatabase("connector_test");) {
            try (JdbcConnection connection = db.connect()) {
                connection.execute("INSERT INTO products VALUES (1001,'roy','old robot',1234.56);");
                connection.query("SELECT * FROM products", rs -> {
                    if (Testing.Print.isEnabled()) connection.print(rs);
                });
            }
        }
        
        // And consume the one insert ...
        records = consumeRecordsByTopic(1);
        assertThat(records.recordsForTopic("kafka-connect.connector_test.products").size()).isEqualTo(1);
        assertThat(records.topics().size()).isEqualTo(1);
        List<SourceRecord> inserts = records.recordsForTopic("kafka-connect.connector_test.products");
        assertInsert(inserts.get(0), "id", 1001);

        // ---------------------------------------------------------------------------------------------------------------
        // Changing the primary key of a row should result in 3 events: INSERT, DELETE, and TOMBSTONE
        // ---------------------------------------------------------------------------------------------------------------
        try (MySQLConnection db = MySQLConnection.forTestDatabase("connector_test");) {
            try (JdbcConnection connection = db.connect()) {
                connection.execute("UPDATE products SET id=2001, description='really old robot' WHERE id=1001");
                connection.query("SELECT * FROM products", rs -> {
                    if (Testing.Print.isEnabled()) connection.print(rs);
                });
            }
        }
        // And consume the update of the PK, which is one insert followed by a delete followed by a tombstone ...
        records = consumeRecordsByTopic(3);
        List<SourceRecord> updates = records.recordsForTopic("kafka-connect.connector_test.products");
        assertThat(updates.size()).isEqualTo(3);
        assertInsert(updates.get(0), "id", 2001);
        assertDelete(updates.get(1), "id", 1001);
        assertTombstone(updates.get(2), "id", 1001);

        // ---------------------------------------------------------------------------------------------------------------
        // Simple UPDATE (with no schema changes)
        // ---------------------------------------------------------------------------------------------------------------
        try (MySQLConnection db = MySQLConnection.forTestDatabase("connector_test");) {
            try (JdbcConnection connection = db.connect()) {
                connection.execute("UPDATE products SET weight=1345.67 WHERE id=2001");
                connection.query("SELECT * FROM products", rs -> {
                    if (Testing.Print.isEnabled()) connection.print(rs);
                });
            }
        }

        // And consume the one update ...
        records = consumeRecordsByTopic(1);
        assertThat(records.topics().size()).isEqualTo(1);
        updates = records.recordsForTopic("kafka-connect.connector_test.products");
        assertThat(updates.size()).isEqualTo(1);
        assertUpdate(updates.get(0), "id", 2001);
        updates.forEach(this::validate);

        // ---------------------------------------------------------------------------------------------------------------
        // Change our schema with a fully-qualified name; we should still see this event
        // ---------------------------------------------------------------------------------------------------------------
        // Add a column with default to the 'products' table and explicitly update one record ...
        try (MySQLConnection db = MySQLConnection.forTestDatabase("connector_test");) {
            try (JdbcConnection connection = db.connect()) {
                connection.execute("ALTER TABLE connector_test.products ADD COLUMN volume FLOAT NOT NULL, ADD COLUMN alias VARCHAR(30) NOT NULL AFTER description");
                connection.execute("UPDATE products SET volume=13.5 WHERE id=2001");
                connection.query("SELECT * FROM products", rs -> {
                    if (Testing.Print.isEnabled()) connection.print(rs);
                });
            }
        }

        // And consume the one schema change event and one update event ...
        records = consumeRecordsByTopic(2);
        assertThat(records.topics().size()).isEqualTo(2);
        assertThat(records.recordsForTopic("kafka-connect").size()).isEqualTo(1);
        updates = records.recordsForTopic("kafka-connect.connector_test.products");
        assertThat(updates.size()).isEqualTo(1);
        assertUpdate(updates.get(0), "id", 2001);
        updates.forEach(this::validate);

        // ---------------------------------------------------------------------------------------------------------------
        // DBZ-55 Change our schema using a different database and a fully-qualified name; we should still see this event
        // ---------------------------------------------------------------------------------------------------------------
        // Connect to a different database, but use the fully qualified name for a table in our database ...
        try (MySQLConnection db = MySQLConnection.forTestDatabase("emptydb");) {
            try (JdbcConnection connection = db.connect()) {
                connection.execute("CREATE TABLE connector_test.stores ("
                        + " id INT(11) PRIMARY KEY NOT NULL AUTO_INCREMENT,"
                        + " first_name VARCHAR(255) NOT NULL,"
                        + " last_name VARCHAR(255) NOT NULL,"
                        + " email VARCHAR(255) NOT NULL );");
            }
        }

        // And consume the one schema change event only ...
        records = consumeRecordsByTopic(1);
        assertThat(records.topics().size()).isEqualTo(1);
        assertThat(records.recordsForTopic("kafka-connect").size()).isEqualTo(1);
        //Testing.Print.enable();
        records.recordsForTopic("kafka-connect").forEach(this::validate);

        // ---------------------------------------------------------------------------------------------------------------
        // Make sure there are no additional events
        // ---------------------------------------------------------------------------------------------------------------
        
        // Do something completely different with a table we've not modified yet and then read that event.
        try (MySQLConnection db = MySQLConnection.forTestDatabase("connector_test");) {
            try (JdbcConnection connection = db.connect()) {
                connection.execute("UPDATE products_on_hand SET quantity=20 WHERE product_id=109");
                connection.query("SELECT * FROM products_on_hand", rs -> {
                    if (Testing.Print.isEnabled()) connection.print(rs);
                });
            }
        }

        // And make sure we consume that one update ...
        records = consumeRecordsByTopic(1);
        assertThat(records.topics().size()).isEqualTo(1);
        updates = records.recordsForTopic("kafka-connect.connector_test.products_on_hand");
        assertThat(updates.size()).isEqualTo(1);
        assertUpdate(updates.get(0), "product_id", 109);
        updates.forEach(this::validate);

        // ---------------------------------------------------------------------------------------------------------------
        // Stop the connector ...
        // ---------------------------------------------------------------------------------------------------------------
        stopConnector();
    }

    @Test
    public void shouldConsumeEventsWithMaskedAndBlacklistedColumns() throws SQLException, InterruptedException {
        Testing.Files.delete(DB_HISTORY_PATH);
        
        // Use the DB configuration to define the connector's configuration ...
        config = Configuration.create()
                              .with(MySqlConnectorConfig.HOSTNAME, System.getProperty("database.hostname"))
                              .with(MySqlConnectorConfig.PORT, System.getProperty("database.port"))
                              .with(MySqlConnectorConfig.USER, "replicator")
                              .with(MySqlConnectorConfig.PASSWORD, "replpass")
                              .with(MySqlConnectorConfig.SERVER_ID, 18780)
                              .with(MySqlConnectorConfig.SERVER_NAME, "kafka-connect-2")
                              .with(MySqlConnectorConfig.INITIAL_BINLOG_FILENAME, "mysql-bin.000001")
                              .with(MySqlConnectorConfig.DATABASE_HISTORY, FileDatabaseHistory.class)
                              .with(MySqlConnectorConfig.DATABASE_WHITELIST, "connector_test")
                              .with(MySqlConnectorConfig.COLUMN_BLACKLIST, "connector_test.orders.order_number")
                              .with(MySqlConnectorConfig.MASK_COLUMN(12), "connector_test.customers.email")
                              .with(MySqlConnectorConfig.INCLUDE_SCHEMA_CHANGES, false)
                              .with(FileDatabaseHistory.FILE_PATH, DB_HISTORY_PATH)
                              .build();

        // Start the connector ...
        start(MySqlConnector.class, config);

        // Consume the first records due to startup and initialization of the database ...
        // Testing.Print.enable();
        SourceRecords records = consumeRecordsByTopic(9+9+4+5);
        assertThat(records.recordsForTopic("kafka-connect-2.connector_test.products").size()).isEqualTo(9);
        assertThat(records.recordsForTopic("kafka-connect-2.connector_test.products_on_hand").size()).isEqualTo(9);
        assertThat(records.recordsForTopic("kafka-connect-2.connector_test.customers").size()).isEqualTo(4);
        assertThat(records.recordsForTopic("kafka-connect-2.connector_test.orders").size()).isEqualTo(5);
        assertThat(records.topics().size()).isEqualTo(4);

        // Check that all records are valid, can be serialized and deserialized ...
        records.forEach(this::validate);
        
        // More records may have been written (if this method were run after the others), but we don't care ...
        stopConnector();

        // Check that the orders.order_number is not present ...
        records.recordsForTopic("kafka-connect-2.connector_test.orders").forEach(record->{
            print(record);
            Struct value = (Struct) record.value();
            try {
                value.get("order_number");
                fail("The 'order_number' field was found but should not exist");
            } catch (DataException e) {
                // expected
                printJson(record);
            }
        });
        
        // Check that the customer.email is masked ...
        records.recordsForTopic("kafka-connect-2.connector_test.customers").forEach(record->{
            Struct value = (Struct) record.value();
            if (value.getStruct("after") != null) {
                assertThat(value.getStruct("after").getString("email")).isEqualTo("************");
            }
            if (value.getStruct("before") != null) {
                assertThat(value.getStruct("before").getString("email")).isEqualTo("************");
            }
            printJson(record);
        });
    }

}
