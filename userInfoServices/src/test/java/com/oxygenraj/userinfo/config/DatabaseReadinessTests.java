package com.oxygenraj.userinfo.config;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class DatabaseReadinessTests {

    private static final String EXPECTED_IDENTITY = "USER_INFO_SCHEMA:USER_INFO_SCHEMA:FREEPDB1";
    private static final String PASSWORD = "test-only-readiness-password";
    private static final String URL = "jdbc:oracle:thin:@//database.example.test:1521/PRIVATEPDB";

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final DatabaseReadiness readiness = new DatabaseReadiness(jdbc);

    @Test
    void correctIdentityAllowsOnlyAReadOnlyEmptyTableProbe() {
        when(jdbc.queryForObject(anyString(), eq(String.class))).thenReturn(EXPECTED_IDENTITY);

        readiness.afterPropertiesSet();

        InOrder order = inOrder(jdbc);
        ArgumentCaptor<String> identityQuery = ArgumentCaptor.forClass(String.class);
        order.verify(jdbc).queryForObject(identityQuery.capture(), eq(String.class));
        assertThat(identityQuery.getValue()).contains(
                "SYS_CONTEXT('USERENV', 'SESSION_USER')",
                "SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA')",
                "SYS_CONTEXT('USERENV', 'CON_NAME')", "FROM dual");
        ArgumentCaptor<String> tableQuery = ArgumentCaptor.forClass(String.class);
        order.verify(jdbc).query(tableQuery.capture(), any(RowCallbackHandler.class));
        assertThat(tableQuery.getValue().strip()).startsWith("SELECT ")
                .contains("ID", "USERNAME", "PASSWORD_HASH", "EMAIL", "PHONE_NUMBER", "USER_ROLE",
                        "ENABLED", "CREATED_AT", "MODIFIED_AT")
                .endsWith("FROM USER_INFO_SCHEMA.USER_DETAILS WHERE 1 = 0");
        verifyNoMoreInteractions(jdbc);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "",
            "SYSTEM:USER_INFO_SCHEMA:FREEPDB1",
            "USER_INFO_SCHEMA:SYSTEM:FREEPDB1",
            "USER_INFO_SCHEMA:USER_INFO_SCHEMA:CDB$ROOT",
            "USER_INFO_SCHEMA:USER_INFO_SCHEMA:OTHERPDB",
            "user_info_schema:USER_INFO_SCHEMA:FREEPDB1",
            PASSWORD
    })
    void wrongUserSchemaOrPdbFailsBeforeProbingTheTableWithoutEchoingIdentity(String identity) {
        when(jdbc.queryForObject(anyString(), eq(String.class))).thenReturn(identity);

        Throwable failure = catchThrowable(readiness::afterPropertiesSet);

        assertSanitized(failure);
        verify(jdbc).queryForObject(anyString(), eq(String.class));
        verify(jdbc, never()).query(anyString(), any(RowCallbackHandler.class));
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void sanitizesAConnectionFailureIncludingItsNestedSqlException() {
        SQLException sqlFailure = sensitiveSqlException(12541);
        DataAccessResourceFailureException databaseFailure = new DataAccessResourceFailureException(
                "Connection details: " + URL + " password=" + PASSWORD, sqlFailure);
        databaseFailure.addSuppressed(new IllegalStateException(PASSWORD));
        when(jdbc.queryForObject(anyString(), eq(String.class))).thenThrow(databaseFailure);

        Throwable failure = catchThrowable(readiness::afterPropertiesSet);

        assertSanitized(failure);
        verify(jdbc, never()).query(anyString(), any(RowCallbackHandler.class));
    }

    @Test
    void sanitizesAMissingTableFailureAfterTheIdentityCheck() {
        when(jdbc.queryForObject(anyString(), eq(String.class))).thenReturn(EXPECTED_IDENTITY);
        BadSqlGrammarException missingTable = new BadSqlGrammarException(
                "Connection details: " + PASSWORD, "sensitive SQL " + URL, sensitiveSqlException(942));
        missingTable.addSuppressed(new IllegalStateException(PASSWORD));
        doThrow(missingTable).when(jdbc).query(anyString(), any(RowCallbackHandler.class));

        Throwable failure = catchThrowable(readiness::afterPropertiesSet);

        assertSanitized(failure);
        InOrder order = inOrder(jdbc);
        order.verify(jdbc).queryForObject(anyString(), eq(String.class));
        order.verify(jdbc).query(anyString(), any(RowCallbackHandler.class));
        verifyNoMoreInteractions(jdbc);
    }

    private static SQLException sensitiveSqlException(int errorCode) {
        return new SQLException("Connection details: " + URL + " password=" + PASSWORD,
                "08006", errorCode, new IllegalArgumentException(PASSWORD));
    }

    private static void assertSanitized(Throwable failure) {
        assertThat(failure).isInstanceOf(IllegalStateException.class).hasNoCause()
                .hasMessageContaining("Database setup check failed")
                .hasMessageContaining("USER_INFO_SCHEMA in FREEPDB1");
        assertThat(failure.getMessage()).doesNotContain(
                PASSWORD, URL, "Connection details:", "sensitive SQL", "OTHERPDB", "CDB$ROOT");
        assertThat(failure.getSuppressed()).isEmpty();
    }
}
