package com.oxygenraj.initial;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OracleSchemaServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private OracleSchemaService service;

    @Test
    void returnsTheVerifiedInitialSchema() {
        when(jdbcTemplate.queryForMap(OracleSchemaService.SESSION_IDENTITY_QUERY))
                .thenReturn(identity("INITIAL_DB", "INITIAL_DB"));

        assertThat(service.requireInitialSchema()).isEqualTo("INITIAL_DB");
        verify(jdbcTemplate).queryForMap(OracleSchemaService.SESSION_IDENTITY_QUERY);
        verifyNoMoreInteractions(jdbcTemplate);
    }

    @ParameterizedTest
    @CsvSource(value = {
            "SYS, INITIAL_DB", "SYSTEM, INITIAL_DB", "EUREKA_DB, INITIAL_DB",
            "INITIAL_DB, SYS", "INITIAL_DB, EUREKA_DB", "INITIAL_DB, initial_db",
            "initial_db, INITIAL_DB", "' INITIAL_DB', INITIAL_DB", "INITIAL_DB, 'INITIAL_DB '",
            "NULL, INITIAL_DB", "INITIAL_DB, NULL", "'', INITIAL_DB", "INITIAL_DB, ''"
    }, nullValues = "NULL")
    void rejectsAnyIdentityThatIsNotExactlyInitialDb(String sessionUser, String currentSchema) {
        when(jdbcTemplate.queryForMap(OracleSchemaService.SESSION_IDENTITY_QUERY))
                .thenReturn(identity(sessionUser, currentSchema));

        assertThatThrownBy(service::requireInitialSchema)
                .isInstanceOf(DatabaseUnavailableException.class)
                .hasMessage("Database is unavailable.");
    }

    @Test
    void translatesDatabaseFailuresToAGenericError() {
        when(jdbcTemplate.queryForMap(OracleSchemaService.SESSION_IDENTITY_QUERY))
                .thenThrow(new DataAccessResourceFailureException("Internal connection details"));

        assertThatThrownBy(service::requireInitialSchema)
                .isInstanceOf(DatabaseUnavailableException.class)
                .hasMessage("Database is unavailable.");
    }

    private Map<String, Object> identity(String sessionUser, String currentSchema) {
        Map<String, Object> identity = new HashMap<>();
        identity.put("SESSION_USER", sessionUser);
        identity.put("CURRENT_SCHEMA", currentSchema);
        return identity;
    }
}
