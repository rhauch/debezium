/*
 * Copyright Debezium Authors.
 * 
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.relational.history;

import java.util.Map;

import io.debezium.config.Configuration;
import io.debezium.relational.Tables;
import io.debezium.relational.ddl.DdlParser;

/**
 * A history of the database schema described by a {@link Tables}. Changes to the database schema can be
 * {@link #record(Map, Map, String, Tables, String) recorded}, and a {@link Tables database schema} can be
 * {@link #record(Map, Map, String, Tables, String) recovered} to various points in that history.
 *
 * @author Randall Hauch
 */
public interface DatabaseHistory {
    
    public static final String CONFIGURATION_FIELD_PREFIX_STRING = "database.history.";
    
    /**
     * Configure this instance.
     * @param config the configuration for this history store
     */
    void configure(Configuration config);
    
    /**
     * Start the history.
     */
    void start();

    /**
     * Record a change to the schema of the named database, and store it in the schema storage.
     * 
     * @param source the information about the source database; may not be null
     * @param position the point in history where these DDL changes were made, which may be used when
     *            {@link #recover(Map, Map, Tables, DdlParser) recovering} the schema to some point in history; may not be
     *            null
     * @param databaseName the name of the database whose schema is being changed; may be null
     * @param schema the current definition of the database schema; may not be null
     * @param ddl the DDL statements that describe the changes to the database schema; may not be null
     */
    void record(Map<String, ?> source, Map<String, ?> position, String databaseName, Tables schema, String ddl);

    /**
     * Recover the {@link Tables database schema} to a known point in its history. Note that it is possible to recover the
     * database schema to a point in history that is earlier than what has been {@link #record(Map, Map, String, Tables, String)
     * recorded}. Likewise, when recovering to a point in history <em>later</em> than what was recorded, the database schema will
     * reflect the latest state known to the history.
     * 
     * @param source the information about the source database; may not be null
     * @param position the point in history at which the {@link Tables database schema} should be recovered; may not be null
     * @param schema the definition of the schema for the named {@code database}; may not be null
     * @param ddlParser the DDL parser that can be used to apply DDL statements to the given {@code schema}; may not be null
     */
    void recover(Map<String, ?> source, Map<String, ?> position, Tables schema, DdlParser ddlParser);
    
    /**
     * Stop recording history and release any resources acquired since {@link #configure(Configuration)}.
     */
    void stop();
}
