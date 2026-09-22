package com.oxygenraj.userinfo.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Fail during startup, not on the first API call. Does not create or alter database objects. */
@Component
@ConditionalOnProperty(name = "user-info.database-check.enabled", havingValue = "true", matchIfMissing = true)
public class DatabaseReadiness implements InitializingBean {
    private final JdbcTemplate jdbc;
    public DatabaseReadiness(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void afterPropertiesSet() {
        try {
            String owner = jdbc.queryForObject("""
                    SELECT SYS_CONTEXT('USERENV', 'SESSION_USER') || ':' ||
                           SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') || ':' ||
                           SYS_CONTEXT('USERENV', 'CON_NAME') FROM dual
                    """, String.class);
            if (!"USER_INFO_SCHEMA:USER_INFO_SCHEMA:FREEPDB1".equals(owner)) {
                throw new IllegalStateException("Unexpected database identity");
            }
            jdbc.query("""
                    SELECT ID, USERNAME, PASSWORD_HASH, EMAIL, PHONE_NUMBER, USER_ROLE, ENABLED,
                           CREATED_AT, MODIFIED_AT FROM USER_INFO_SCHEMA.USER_DETAILS WHERE 1 = 0
                    """, row -> {});
        }
        catch (RuntimeException exception) {
            // Do not include raw JDBC errors, which may contain connection details or secrets.
            throw new IllegalStateException("Database setup check failed. Use USER_INFO_SCHEMA in FREEPDB1, "
                    + "set USER_INFO_DB_PASSWORD, keep the SSH tunnel active, and execute the supplied SQL scripts.");
        }
    }
}
