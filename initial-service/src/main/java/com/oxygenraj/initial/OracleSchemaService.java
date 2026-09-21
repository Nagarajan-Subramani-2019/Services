package com.oxygenraj.initial;

import java.util.Map;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class OracleSchemaService {

    static final String SESSION_IDENTITY_QUERY = """
            SELECT SYS_CONTEXT('USERENV','SESSION_USER') AS SESSION_USER,
                   SYS_CONTEXT('USERENV','CURRENT_SCHEMA') AS CURRENT_SCHEMA
            FROM dual
            """;
    private static final String EXPECTED_SCHEMA = "INITIAL_DB";

    private final JdbcTemplate jdbcTemplate;

    public OracleSchemaService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public String requireInitialSchema() {
        final Map<String, Object> identity;
        try {
            identity = jdbcTemplate.queryForMap(SESSION_IDENTITY_QUERY);
        } catch (DataAccessException exception) {
            throw new DatabaseUnavailableException(exception);
        }

        if (!EXPECTED_SCHEMA.equals(identity.get("SESSION_USER"))
                || !EXPECTED_SCHEMA.equals(identity.get("CURRENT_SCHEMA"))) {
            throw new DatabaseUnavailableException();
        }
        return (String) identity.get("CURRENT_SCHEMA");
    }
}
