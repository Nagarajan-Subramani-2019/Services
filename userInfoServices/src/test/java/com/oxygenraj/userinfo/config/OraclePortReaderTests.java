package com.oxygenraj.userinfo.config;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OraclePortReaderTests {

    private static final String URL = "jdbc:oracle:thin:@//database.example.test:1521/TESTPDB";
    private static final String PASSWORD = "test-only-database-password";

    private final OraclePortReader.ConnectionFactory connections = mock(OraclePortReader.ConnectionFactory.class);
    private final Connection connection = mock(Connection.class);
    private final PreparedStatement statement = mock(PreparedStatement.class);
    private final ResultSet rows = mock(ResultSet.class);
    private final OraclePortReader reader = new OraclePortReader(connections);

    @BeforeEach
    void configureOneValidRow() throws SQLException {
        when(connections.open(anyString(), any(Properties.class))).thenReturn(connection);
        when(connection.prepareStatement(OraclePortReader.PORT_QUERY)).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, false);
        when(rows.getString("SESSION_USER")).thenReturn("USER_INFO_SCHEMA");
        when(rows.getString("CURRENT_SCHEMA")).thenReturn("USER_INFO_SCHEMA");
        when(rows.getString("VALUE")).thenReturn("8082");
    }

    @Test
    void usesTheFixedSchemaAndExactFiltersWithBoundedJdbcTimeouts() throws SQLException {
        assertThat(reader.readPort(URL, PASSWORD)).isEqualTo(8082);

        ArgumentCaptor<Properties> properties = ArgumentCaptor.forClass(Properties.class);
        verify(connections).open(eq(URL), properties.capture());
        assertThat(properties.getValue()).containsOnlyKeys(
                "user", "password", "oracle.net.CONNECT_TIMEOUT", "oracle.jdbc.ReadTimeout");
        assertThat(properties.getValue().getProperty("user")).isEqualTo("USER_INFO_SCHEMA");
        assertThat(properties.getValue().getProperty("password")).isEqualTo(PASSWORD);
        assertThat(properties.getValue().getProperty("oracle.net.CONNECT_TIMEOUT")).isEqualTo("5000");
        assertThat(properties.getValue().getProperty("oracle.jdbc.ReadTimeout")).isEqualTo("10000");
        assertThat(OraclePortReader.PORT_QUERY).contains("FROM USER_INFO_SCHEMA.PROPERTIES")
                .contains("SESSION_USER", "CURRENT_SCHEMA").doesNotContain(PASSWORD, URL);
        verify(connection).prepareStatement(OraclePortReader.PORT_QUERY);
        verify(statement).setQueryTimeout(5);
        verify(statement).setString(1, "userInfoServices");
        verify(statement).setString(2, "jdbc");
        verify(statement).setString(3, "jdbc");
        verify(statement).setString(4, "server.port");
        assertAllJdbcResourcesClosed();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "65535", "00080", " 8082 "})
    void acceptsValidPortBoundariesAndWhitespace(String value) throws SQLException {
        when(rows.getString("VALUE")).thenReturn(value);

        assertThat(reader.readPort(URL, PASSWORD)).isEqualTo(Integer.parseInt(value.trim()));

        assertAllJdbcResourcesClosed();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "0", "65536", "999999", "-1", "+1", "1.0", "1e3", PASSWORD})
    void rejectsInvalidPortsWithoutEchoingTheDatabaseValue(String value) throws SQLException {
        when(rows.getString("VALUE")).thenReturn(value);

        Throwable failure = catchThrowable(() -> reader.readPort(URL, PASSWORD));

        assertSanitized(failure);
        assertThat(failure).hasMessageContaining("1 to 65535");
        assertAllJdbcResourcesClosed();
    }

    @Test
    void rejectsAMissingRowAndClosesResources() throws SQLException {
        when(rows.next()).thenReturn(false);

        Throwable failure = catchThrowable(() -> reader.readPort(URL, PASSWORD));

        assertSanitized(failure);
        assertThat(failure).hasMessageContaining("Missing");
        assertAllJdbcResourcesClosed();
    }

    @Test
    void rejectsDuplicateRowsEvenWhenTheFirstPortIsValid() throws SQLException {
        when(rows.next()).thenReturn(true, true);

        Throwable failure = catchThrowable(() -> reader.readPort(URL, PASSWORD));

        assertSanitized(failure);
        assertThat(failure).hasMessageContaining("exactly one");
        assertAllJdbcResourcesClosed();
    }

    @ParameterizedTest
    @CsvSource({
            "SYSTEM, USER_INFO_SCHEMA",
            "USER_INFO_SCHEMA, SYSTEM",
            "user_info_schema, USER_INFO_SCHEMA",
            "USER_INFO_SCHEMA, user_info_schema"
    })
    void rejectsAChangedSessionUserOrCurrentSchema(String sessionUser, String schema) throws SQLException {
        when(rows.getString("SESSION_USER")).thenReturn(sessionUser);
        when(rows.getString("CURRENT_SCHEMA")).thenReturn(schema);

        Throwable failure = catchThrowable(() -> reader.readPort(URL, PASSWORD));

        assertSanitized(failure);
        assertThat(failure).hasMessageContaining("must run as USER_INFO_SCHEMA in USER_INFO_SCHEMA");
        assertAllJdbcResourcesClosed();
    }

    @Test
    void sanitizesConnectionFailuresWithoutRetainingTheirCause() throws SQLException {
        when(connections.open(anyString(), any(Properties.class))).thenThrow(sensitiveSqlException());

        Throwable failure = catchThrowable(() -> reader.readPort(URL, PASSWORD));

        assertSanitized(failure);
        assertThat(failure).hasMessageContaining("12541");
    }

    @Test
    void closesTheConnectionWhenPreparingTheStatementFails() throws SQLException {
        when(connection.prepareStatement(OraclePortReader.PORT_QUERY)).thenThrow(sensitiveSqlException());

        assertSanitized(catchThrowable(() -> reader.readPort(URL, PASSWORD)));

        verify(connection).close();
    }

    @Test
    void sanitizesQueryFailuresAndClosesTheStatementAndConnection() throws SQLException {
        when(statement.executeQuery()).thenThrow(sensitiveSqlException());

        assertSanitized(catchThrowable(() -> reader.readPort(URL, PASSWORD)));

        verify(statement).close();
        verify(connection).close();
    }

    @Test
    void sanitizesResultSetFailuresAndClosesEveryResource() throws SQLException {
        when(rows.getString("VALUE")).thenThrow(sensitiveSqlException());

        assertSanitized(catchThrowable(() -> reader.readPort(URL, PASSWORD)));

        assertAllJdbcResourcesClosed();
    }

    @Test
    void sanitizesCloseFailuresAfterAnOtherwiseValidResult() throws SQLException {
        doThrow(sensitiveSqlException()).when(rows).close();

        assertSanitized(catchThrowable(() -> reader.readPort(URL, PASSWORD)));

        assertAllJdbcResourcesClosed();
    }

    @Test
    void doesNotExposeSuppressedSqlExceptionsWhenValidationAndCleanupBothFail() throws SQLException {
        when(rows.getString("VALUE")).thenReturn("0");
        doThrow(sensitiveSqlException()).when(rows).close();
        doThrow(sensitiveSqlException()).when(statement).close();
        doThrow(sensitiveSqlException()).when(connection).close();

        assertSanitized(catchThrowable(() -> reader.readPort(URL, PASSWORD)));

        assertAllJdbcResourcesClosed();
    }

    private static SQLException sensitiveSqlException() {
        return new SQLException("Connection details: " + URL + " password=" + PASSWORD,
                "08006", 12541, new IllegalArgumentException(PASSWORD));
    }

    private static void assertSanitized(Throwable failure) {
        assertThat(failure).isInstanceOf(IllegalStateException.class).hasNoCause();
        assertThat(failure.getMessage()).doesNotContain(PASSWORD, URL, "Connection details:");
        assertThat(failure.getSuppressed()).isEmpty();
    }

    private void assertAllJdbcResourcesClosed() throws SQLException {
        verify(rows).close();
        verify(statement).close();
        verify(connection).close();
    }
}
