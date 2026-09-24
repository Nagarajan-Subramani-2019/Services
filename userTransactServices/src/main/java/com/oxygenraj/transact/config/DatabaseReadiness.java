package com.oxygenraj.transact.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Validates existing objects without DDL, writes, or consuming a sequence value. */
@Component
@ConditionalOnProperty(name = "user-transact.database-check.enabled", havingValue = "true", matchIfMissing = true)
public class DatabaseReadiness implements InitializingBean {
    private final JdbcTemplate jdbc;

    public DatabaseReadiness(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void afterPropertiesSet() {
        try {
            String identity = jdbc.queryForObject("""
                    SELECT SYS_CONTEXT('USERENV', 'SESSION_USER') || ':' ||
                           SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') FROM dual
                    """, String.class);
            if (!"USER_TRANSACT_SCHEMA:USER_TRANSACT_SCHEMA".equals(identity)) {
                throw new IllegalStateException("Unexpected database identity");
            }
            jdbc.query("""
                    SELECT ID, USER_ID, MONTH_NAME, MONTH_COUNT, AMOUNT, VERSION, CREATED_AT, MODIFIED_AT
                      FROM USER_TRANSACT_SCHEMA."TRANSACTION" WHERE 1 = 0
                    """, row -> {});
            Integer sequenceCount = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM USER_SEQUENCES WHERE SEQUENCE_NAME = 'TRANSACTION_ID_SEQ'
                    """, Integer.class);
            if (!Integer.valueOf(1).equals(sequenceCount)) {
                throw new IllegalStateException("Missing transaction sequence");
            }
        }
        catch (RuntimeException exception) {
            // JDBC failures can contain connection settings, SQL, and nested secrets.
            throw new IllegalStateException("Database setup check failed. Use USER_TRANSACT_SCHEMA, "
                    + "set USER_TRANSACT_DB_PASSWORD, verify the configured Oracle connection, "
                    + "and execute the supplied transaction schema SQL scripts before startup.");
        }
    }
}
