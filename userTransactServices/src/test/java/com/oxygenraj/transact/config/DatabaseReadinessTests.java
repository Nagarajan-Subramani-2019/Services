package com.oxygenraj.transact.config;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
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
    private static final String IDENTITY = "USER_TRANSACT_SCHEMA:USER_TRANSACT_SCHEMA";
    private static final String SECRET = "test-only-readiness-secret";
    private static final String URL = "jdbc:oracle:thin:@//private.example.test:1521/PRIVATEPDB";
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final DatabaseReadiness readiness = new DatabaseReadiness(jdbc);

    @Test
    void verifiesOwnerTableColumnsAndSequenceWithoutDdlWritesOrNextval() {
        when(jdbc.queryForObject(anyString(), eq(String.class))).thenReturn(IDENTITY);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);

        readiness.afterPropertiesSet();

        InOrder order = inOrder(jdbc);
        ArgumentCaptor<String> identity = ArgumentCaptor.forClass(String.class);
        order.verify(jdbc).queryForObject(identity.capture(), eq(String.class));
        assertThat(identity.getValue()).contains("SESSION_USER", "CURRENT_SCHEMA", "FROM dual")
                .doesNotContain("CON_NAME", "FREEPDB1");
        ArgumentCaptor<String> table = ArgumentCaptor.forClass(String.class);
        order.verify(jdbc).query(table.capture(), any(RowCallbackHandler.class));
        assertThat(table.getValue().strip()).startsWith("SELECT ")
                .contains("ID", "USER_ID", "MONTH_NAME", "MONTH_COUNT", "AMOUNT", "VERSION", "CREATED_AT", "MODIFIED_AT")
                .endsWith("FROM USER_TRANSACT_SCHEMA.\"TRANSACTION\" WHERE 1 = 0");
        ArgumentCaptor<String> sequence = ArgumentCaptor.forClass(String.class);
        order.verify(jdbc).queryForObject(sequence.capture(), eq(Integer.class));
        assertThat(sequence.getValue()).contains("SELECT COUNT(*) FROM USER_SEQUENCES", "'TRANSACTION_ID_SEQ'")
                .doesNotContain("NEXTVAL", "INSERT", "UPDATE", "CREATE", "ALTER", "GRANT");
        verifyNoMoreInteractions(jdbc);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "SYSTEM:USER_TRANSACT_SCHEMA", "USER_TRANSACT_SCHEMA:SYSTEM",
            "USER_INFO_SCHEMA:USER_INFO_SCHEMA", "user_transact_schema:USER_TRANSACT_SCHEMA", SECRET})
    void rejectsWrongIdentityBeforeProbingObjects(String identity) {
        when(jdbc.queryForObject(anyString(), eq(String.class))).thenReturn(identity);

        assertSanitized(catchThrowable(readiness::afterPropertiesSet));

        verify(jdbc).queryForObject(anyString(), eq(String.class));
        verifyNoMoreInteractions(jdbc);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, 2})
    void requiresTheExistingOwnedSequence(Integer count) {
        when(jdbc.queryForObject(anyString(), eq(String.class))).thenReturn(IDENTITY);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(count);

        assertSanitized(catchThrowable(readiness::afterPropertiesSet));
    }

    @Test
    void sanitizesConnectionAndNestedDriverErrors() {
        when(jdbc.queryForObject(anyString(), eq(String.class))).thenThrow(databaseFailure());

        assertSanitized(catchThrowable(readiness::afterPropertiesSet));

        verify(jdbc, never()).query(anyString(), any(RowCallbackHandler.class));
    }

    @Test
    void missingTableOrColumnFailsBeforeSequenceCheck() {
        when(jdbc.queryForObject(anyString(), eq(String.class))).thenReturn(IDENTITY);
        doThrow(databaseFailure()).when(jdbc).query(anyString(), any(RowCallbackHandler.class));

        assertSanitized(catchThrowable(readiness::afterPropertiesSet));

        verify(jdbc, never()).queryForObject(anyString(), eq(Integer.class));
    }

    @Test
    void sanitizesSequenceMetadataErrors() {
        when(jdbc.queryForObject(anyString(), eq(String.class))).thenReturn(IDENTITY);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenThrow(databaseFailure());

        assertSanitized(catchThrowable(readiness::afterPropertiesSet));
    }

    private static RuntimeException databaseFailure() {
        var failure = new DataAccessResourceFailureException("Connection details: " + URL + SECRET,
                new SQLException("Sensitive SQL " + SECRET, "08006", 12541));
        failure.addSuppressed(new IllegalStateException(SECRET));
        return failure;
    }

    private static void assertSanitized(Throwable failure) {
        assertThat(failure).isInstanceOf(IllegalStateException.class).hasNoCause()
                .hasMessageContaining("Database setup check failed")
                .hasMessageContaining("USER_TRANSACT_SCHEMA");
        assertThat(failure.getMessage()).doesNotContain(SECRET, URL, "Connection details", "Sensitive SQL");
        assertThat(failure.getSuppressed()).isEmpty();
    }
}
