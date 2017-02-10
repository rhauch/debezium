/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.debezium.connector.postgresql;

import static io.debezium.connector.postgresql.PostgresConnectorConfig.SCHEMA_BLACKLIST;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.stream.IntStream;

import org.apache.kafka.connect.data.Decimal;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.junit.Before;
import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;

import io.debezium.connector.postgresql.connection.PostgresConnection;
import io.debezium.data.Bits;
import io.debezium.data.Json;
import io.debezium.data.OptionalSchema;
import io.debezium.data.Uuid;
import io.debezium.data.Xml;
import io.debezium.data.geometry.Point;
import io.debezium.relational.TableId;
import io.debezium.relational.TableSchema;
import io.debezium.time.Date;
import io.debezium.time.MicroDuration;
import io.debezium.time.NanoTime;
import io.debezium.time.NanoTimestamp;
import io.debezium.time.ZonedTime;
import io.debezium.time.ZonedTimestamp;
import io.debezium.util.AvroValidator;

/**
 * Unit test for {@link PostgresSchema}
 *
 * @author Horia Chiorean (hchiorea@redhat.com)
 */
public class PostgresSchemaIT {
    
    private static final String[] TEST_TABLES = new String[] { "public.numeric_table", "public.string_table", "public.cash_table",
                                                               "public.bitbin_table",
                                                               "public.time_table", "public.text_table", "public.geom_table" };
    
    private PostgresSchema schema;
    
    @Before
    public void before() throws SQLException {
        TestHelper.dropAllSchemas();
    }
    
    @Test
    public void shouldLoadSchemaForBuiltinPostgresTypes() throws Exception {
        TestHelper.executeDDL("postgres_create_tables.ddl");
        schema = new PostgresSchema(new PostgresConnectorConfig(TestHelper.defaultConfig().build()));
        try (PostgresConnection connection = TestHelper.create()) {
            schema.refresh(connection, false);
            assertTablesIncluded(TEST_TABLES);
            Arrays.stream(TEST_TABLES).forEach(tableId -> assertKeySchema(tableId, "pk", Schema.INT32_SCHEMA));
            assertTableSchema("public.numeric_table", "si, i, bi, d, n, r, db, ss, bs, b",
                              Schema.OPTIONAL_INT16_SCHEMA, Schema.OPTIONAL_INT32_SCHEMA, Schema.OPTIONAL_INT64_SCHEMA,
                              Decimal.builder(1).optional().defaultValue(null).build(),
                              Decimal.builder(2).optional().defaultValue(null).build(), Schema.OPTIONAL_FLOAT32_SCHEMA,
                              Schema.OPTIONAL_FLOAT64_SCHEMA, Schema.INT16_SCHEMA, Schema.INT64_SCHEMA, Schema.OPTIONAL_BOOLEAN_SCHEMA);
            assertTableSchema("public.string_table", "vc, vcv, ch, c, t",
                              Schema.OPTIONAL_STRING_SCHEMA, Schema.OPTIONAL_STRING_SCHEMA, Schema.OPTIONAL_STRING_SCHEMA,
                              Schema.OPTIONAL_STRING_SCHEMA, Schema.OPTIONAL_STRING_SCHEMA);
            assertTableSchema("public.cash_table", "csh", Decimal.builder(0).optional().defaultValue(null).build());
            assertTableSchema("public.bitbin_table", "ba, bol, bs, bv",
                              Schema.OPTIONAL_BYTES_SCHEMA, Schema.OPTIONAL_BOOLEAN_SCHEMA,
                              Bits.builder(2).optional().defaultValue(null).build(),
                              Bits.builder(2).optional().defaultValue(null).build());
            assertTableSchema("public.time_table", "ts, tz, date, ti, ttz, it",
                              NanoTimestamp.builder().optional().defaultValue(null).build(),
                              ZonedTimestamp.builder().optional().defaultValue(null).build(),
                              Date.builder().optional().defaultValue(null).build(),
                              NanoTime.builder().optional().defaultValue(null).build(),
                              ZonedTime.builder().optional().defaultValue(null).build(),
                              MicroDuration.builder().optional().defaultValue(null).build());
            assertTableSchema("public.text_table", "j, jb, x, u",
                              Json.builder().optional().defaultValue(null).build(),
                              Json.builder().optional().defaultValue(null).build(),
                              Xml.builder().optional().defaultValue(null).build(),
                              Uuid.builder().optional().defaultValue(null).build());
            assertTableSchema("public.geom_table", "p", Point.builder().optional().defaultValue(null).build());
        }
    }
    
    @Test
    public void shouldApplyFilters() throws Exception {
        String statements = "CREATE SCHEMA s1; " +
                            "CREATE SCHEMA s2; " +
                            "DROP TABLE IF EXISTS s1.A;" +
                            "DROP TABLE IF EXISTS s1.B;" +
                            "DROP TABLE IF EXISTS s2.A;" +
                            "DROP TABLE IF EXISTS s2.B;" +
                            "CREATE TABLE s1.A (pk SERIAL, aa integer, PRIMARY KEY(pk));" +
                            "CREATE TABLE s1.B (pk SERIAL, ba integer, PRIMARY KEY(pk));" +
                            "CREATE TABLE s2.A (pk SERIAL, aa integer, PRIMARY KEY(pk));" +
                            "CREATE TABLE s2.B (pk SERIAL, ba integer, PRIMARY KEY(pk));";
        TestHelper.execute(statements);
        PostgresConnectorConfig config = new PostgresConnectorConfig(TestHelper.defaultConfig().with(SCHEMA_BLACKLIST, "s1").build());
        schema = new PostgresSchema(config);
        try (PostgresConnection connection = TestHelper.create()) {
            schema.refresh(connection, false);
            assertTablesIncluded("s2.a", "s2.b");
            assertTablesExcluded("s1.a", "s1.b");
        }
        
        config = new PostgresConnectorConfig(TestHelper.defaultConfig().with(SCHEMA_BLACKLIST, "s.*").build());
        schema = new PostgresSchema(config);
        try (PostgresConnection connection = TestHelper.create()) {
            schema.refresh(connection, false);
            assertTablesExcluded("s1.a", "s2.a", "s1.b", "s2.b");
        }
        
        config = new PostgresConnectorConfig(TestHelper.defaultConfig().with(PostgresConnectorConfig.TABLE_BLACKLIST, "s1.A,s2.A").build());
        schema = new PostgresSchema(config);
        try (PostgresConnection connection = TestHelper.create()) {
            schema.refresh(connection, false);
            assertTablesIncluded("s1.b", "s2.b");
            assertTablesExcluded("s1.a", "s2.a");
        }
        
        config = new PostgresConnectorConfig(TestHelper.defaultConfig()
                                                          .with(SCHEMA_BLACKLIST, "s2")
                                                          .with(PostgresConnectorConfig.TABLE_BLACKLIST, "s1.A")
                                                          .build());
        schema = new PostgresSchema(config);
        try (PostgresConnection connection = TestHelper.create()) {
            schema.refresh(connection, false);
            assertTablesIncluded("s1.b");
            assertTablesExcluded("s1.a", "s2.a", "s2.b");
        }
        config = new PostgresConnectorConfig(TestHelper.defaultConfig().with(PostgresConnectorConfig.COLUMN_BLACKLIST, ".*aa")
                                                       .build());
        schema = new PostgresSchema(config);
        try (PostgresConnection connection = TestHelper.create()) {
            schema.refresh(connection, false);
            assertColumnsExcluded("s1.a.aa", "s2.a.aa");
        }
    }
    
    @Test
    public void shouldDetectNewChangesAfterRefreshing() throws Exception {
        String statements = "CREATE SCHEMA public;" +
                            "DROP TABLE IF EXISTS table1;" +
                            "CREATE TABLE table1 (pk SERIAL,  PRIMARY KEY(pk));";
        TestHelper.execute(statements);
        schema = new PostgresSchema(new PostgresConnectorConfig(TestHelper.defaultConfig().build()));
        try (PostgresConnection connection = TestHelper.create()) {
            schema.refresh(connection, false);
            assertTablesIncluded("public.table1");
        }
        statements = "DROP TABLE IF EXISTS table1;" +
                     "DROP TABLE IF EXISTS table2;" +
                     "CREATE TABLE table2 (pk SERIAL, strcol VARCHAR, PRIMARY KEY(pk));";
        TestHelper.execute(statements);
        String tableId = "public.table2";
        try (PostgresConnection connection = TestHelper.create()) {
            schema.refresh(connection, false);
            assertTablesIncluded(tableId);
            assertTablesExcluded("public.table1");
            assertTableSchema(tableId, "strcol", OptionalSchema.OPTIONAL_STRING_SCHEMA);
        }
        
        statements = "ALTER TABLE table2 ADD COLUMN vc VARCHAR(2);" +
                     "ALTER TABLE table2 ADD COLUMN si SMALLINT;" +
                     "ALTER TABLE table2 DROP COLUMN strcol;";
                    
        TestHelper.execute(statements);
        try (PostgresConnection connection = TestHelper.create()) {
            schema.refresh(connection, TableId.parse(tableId, false));
            assertTablesIncluded(tableId);
            assertTablesExcluded("public.table1");
            assertTableSchema(tableId, "vc, si",
                              OptionalSchema.OPTIONAL_STRING_SCHEMA, OptionalSchema.OPTIONAL_INT16_SCHEMA);
            assertColumnsExcluded(tableId + ".strcol");
        }
    }
    
    protected void assertKeySchema(String fullyQualifiedTableName, String fields, Schema... types) {
        TableSchema tableSchema = schema.schemaFor(fullyQualifiedTableName);
        Schema keySchema = tableSchema.keySchema();
        assertSchemaContent(fields.split(","), types, keySchema);
    }

    protected void assertTableSchema(String fullyQualifiedTableName, String fields, Schema... types) {
        TableSchema tableSchema = schema.schemaFor(fullyQualifiedTableName);
        Schema keySchema = tableSchema.valueSchema();
        assertSchemaContent(fields.split(","), types, keySchema);
    }
    
    private void assertSchemaContent(String[] fields, Schema[] types, Schema keySchema) {
        IntStream.range(0, fields.length).forEach(i -> {
            String fieldName = fields[i].trim();
            Field field = keySchema.field(fieldName);
            assertNotNull(fieldName + " not found in schema", field);
            assertEquals("'" + fieldName + "' has incorrect schema.", types[i], field.schema());
        });
    }
    
    protected void assertTablesIncluded(String... fullyQualifiedTableNames) {
        Arrays.stream(fullyQualifiedTableNames).forEach(fullyQualifiedTableName -> {
            TableSchema tableSchema = schema.schemaFor(fullyQualifiedTableName);
            assertNotNull(fullyQualifiedTableName + " not included", tableSchema);
            assertThat(tableSchema.keySchema().name()).isEqualTo(validFullName(fullyQualifiedTableName, ".Key"));
            assertThat(tableSchema.valueSchema().name()).isEqualTo(validFullName(fullyQualifiedTableName, ".Value"));
        });
    }
    
    private String validFullName(String proposedName, String suffix) {
        return AvroValidator.validFullname(TestHelper.TEST_SERVER + "." + proposedName + suffix);
    }
    
    protected void assertTablesExcluded(String... fullyQualifiedTableNames) {
        Arrays.stream(fullyQualifiedTableNames).forEach(fullyQualifiedTableName -> {
            assertThat(schema.tableFor(fullyQualifiedTableName)).isNull();
            assertThat(schema.schemaFor(fullyQualifiedTableName)).isNull();
        });
    }
    
    protected void assertColumnsExcluded(String...columnNames) {
        Arrays.stream(columnNames).forEach(fqColumnName -> {
            int lastDotIdx = fqColumnName.lastIndexOf(".");
            String fullyQualifiedTableName = fqColumnName.substring(0, lastDotIdx);
            String columnName = lastDotIdx > 0 ? fqColumnName.substring(lastDotIdx + 1) : fqColumnName;
            TableSchema tableSchema = schema.schemaFor(fullyQualifiedTableName);
            assertNotNull(fullyQualifiedTableName + " not included", tableSchema);
            Schema valueSchema = tableSchema.valueSchema();
            assertNotNull(fullyQualifiedTableName + ".Value schema not included", valueSchema);
            assertNull(columnName + " not excluded;", valueSchema.field(columnName));
        });
    }
}
