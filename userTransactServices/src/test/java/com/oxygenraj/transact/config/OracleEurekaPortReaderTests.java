package com.oxygenraj.transact.config;

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

class OracleEurekaPortReaderTests {

    private static final String URL = "jdbc:oracle:thin:@//database.example.test:1521/FREEPDB1";
    private static final String PASSWORD = "test-only-database-password";

    private final OracleEurekaPortReader.ConnectionFactory connections =
            mock(OracleEurekaPortReader.ConnectionFactory.class);
    private final Connection connection = mock(Connection.class);
    private final PreparedStatement statement = mock(PreparedStatement.class);
    private final ResultSet rows = mock(ResultSet.class);
    private final OracleEurekaPortReader reader = new OracleEurekaPortReader(connections);

    @BeforeEach
    void configureOneValidRow() throws SQLException {
        when(connections.open(anyString(), any(Properties.class))).thenReturn(connection);
        when(connection.prepareStatement(OracleEurekaPortReader.PORT_QUERY)).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, false);
        when(rows.getString("SESSION_USER")).thenReturn("EUREKA_DB");
        when(rows.getString("CURRENT_SCHEMA")).thenReturn("EUREKA_DB");
        when(rows.getString("VALUE")).thenReturn("8760");
    }

    @Test
    void readsTheExactExistingEurekaSettingUsingThePropertiesAccountAndBoundedTimeouts() throws SQLException {
        assertThat(reader.readPort(URL, PASSWORD)).isEqualTo(8760);

        ArgumentCaptor<Properties> properties = ArgumentCaptor.forClass(Properties.class);
        verify(connections).open(eq(URL), properties.capture());
        assertThat(properties.getValue()).containsOnlyKeys(
                "user", "password", "oracle.net.CONNECT_TIMEOUT", "oracle.jdbc.ReadTimeout");
        assertThat(properties.getValue().getProperty("user")).isEqualTo("EUREKA_DB");
        assertThat(properties.getValue().getProperty("password")).isEqualTo(PASSWORD);
        assertThat(properties.getValue().getProperty("oracle.net.CONNECT_TIMEOUT")).isEqualTo("5000");
        assertThat(properties.getValue().getProperty("oracle.jdbc.ReadTimeout")).isEqualTo("10000");
        assertThat(OracleEurekaPortReader.PORT_QUERY.strip()).startsWith("SELECT ")
                .contains("FROM EUREKA_DB.PROPERTIES", "SESSION_USER", "CURRENT_SCHEMA")
                .doesNotContain("ALTER", "CREATE", "GRANT", PASSWORD, URL);
        verify(connection).prepareStatement(OracleEurekaPortReader.PORT_QUERY);
        verify(statement).setQueryTimeout(5);
        verify(statement).setString(1, "eureka-server");
        verify(statement).setString(2, "jdbc");
        verify(statement).setString(3, "jdbc");
        verify(statement).setString(4, "server.port");
        assertAllJdbcResourcesClosed();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "65535", "00080", " 8760 "})
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
    void failsClosedForAMissingRowAndClosesResources() throws SQLException {
        when(rows.next()).thenReturn(false);

        Throwable failure = catchThrowable(() -> reader.readPort(URL, PASSWORD));

        assertSanitized(failure);
        assertThat(failure).hasMessageContaining("Missing EUREKA_DB.PROPERTIES row")
                .hasMessageContaining("eureka-server / jdbc / jdbc / server.port");
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
            "USER_TRANSACT_SCHEMA, USER_TRANSACT_SCHEMA",
            "USER_TRANSACT_SCHEMA, EUREKA_DB",
            "EUREKA_DB, USER_TRANSACT_SCHEMA",
            "SYSTEM, EUREKA_DB",
            "EUREKA_DB, SYSTEM",
            "eureka_db, EUREKA_DB",
            "EUREKA_DB, eureka_db"
    })
    void rejectsWrongSessionUserOrSchema(String sessionUser, String schema) throws SQLException {
        when(rows.getString("SESSION_USER")).thenReturn(sessionUser);
        when(rows.getString("CURRENT_SCHEMA")).thenReturn(schema);

        Throwable failure = catchThrowable(() -> reader.readPort(URL, PASSWORD));

        assertSanitized(failure);
        assertThat(failure).hasMessageContaining("EUREKA_DB in the EUREKA_DB schema");
        assertAllJdbcResourcesClosed();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SESSION_USER", "CURRENT_SCHEMA"})
    void rejectsMissingIdentityFields(String field) throws SQLException {
        when(rows.getString(field)).thenReturn(null);

        assertSanitized(catchThrowable(() -> reader.readPort(URL, PASSWORD)));

        assertAllJdbcResourcesClosed();
    }

    @Test
    void sanitizesConnectionFailuresWithoutRetainingTheirCause() throws SQLException {
        when(connections.open(anyString(), any(Properties.class))).thenThrow(sensitiveSqlException(12541));

        Throwable failure = catchThrowable(() -> reader.readPort(URL, PASSWORD));

        assertSanitized(failure);
        assertThat(failure).hasMessageContaining("12541");
    }

    @Test
    void closesTheConnectionWhenPreparingTheStatementFails() throws SQLException {
        when(connection.prepareStatement(OracleEurekaPortReader.PORT_QUERY)).thenThrow(sensitiveSqlException(942));

        assertSanitized(catchThrowable(() -> reader.readPort(URL, PASSWORD)));

        verify(connection).close();
    }

    @ParameterizedTest
    @ValueSource(ints = {942, 1031})
    void missingTableOrAccessReportsPropertiesDatabaseSetupWithoutExposingJdbcDetails(int errorCode) throws SQLException {
        when(statement.executeQuery()).thenThrow(sensitiveSqlException(errorCode));

        Throwable failure = catchThrowable(() -> reader.readPort(URL, PASSWORD));

        assertSanitized(failure);
        assertThat(failure).hasMessageContaining(Integer.toString(errorCode))
                .hasMessageContaining("EUREKA_DB credentials")
                .hasMessageContaining("EUREKA_DB.PROPERTIES table and configured PDB")
                .hasMessageNotContaining("grant USER_TRANSACT_SCHEMA");
        verify(statement).close();
        verify(connection).close();
    }

    @Test
    void sanitizesResultSetFailuresAndClosesEveryResource() throws SQLException {
        when(rows.getString("VALUE")).thenThrow(sensitiveSqlException(17002));

        assertSanitized(catchThrowable(() -> reader.readPort(URL, PASSWORD)));

        assertAllJdbcResourcesClosed();
    }

    @Test
    void sanitizesCloseFailuresAfterAnOtherwiseValidResult() throws SQLException {
        doThrow(sensitiveSqlException(17002)).when(rows).close();

        assertSanitized(catchThrowable(() -> reader.readPort(URL, PASSWORD)));

        assertAllJdbcResourcesClosed();
    }

    @Test
    void stripsSuppressedSqlExceptionsWhenValidationAndCleanupBothFail() throws SQLException {
        when(rows.getString("VALUE")).thenReturn("0");
        doThrow(sensitiveSqlException(17002)).when(rows).close();
        doThrow(sensitiveSqlException(17002)).when(statement).close();
        doThrow(sensitiveSqlException(17002)).when(connection).close();

        Throwable failure = catchThrowable(() -> reader.readPort(URL, PASSWORD));

        assertSanitized(failure);
        assertThat(failure).hasMessageContaining("1 to 65535");
        assertAllJdbcResourcesClosed();
    }

    private static SQLException sensitiveSqlException(int errorCode) {
        return new SQLException("Connection details: " + URL + " password=" + PASSWORD,
                "08006", errorCode, new IllegalArgumentException(PASSWORD));
    }

    private static void assertSanitized(Throwable failure) {
        assertThat(failure).isInstanceOf(IllegalStateException.class).hasNoCause();
        assertThat(failure.getMessage()).doesNotContain(PASSWORD, URL, "Connection details:", "OTHERPDB", "CDB$ROOT");
        assertThat(failure.getSuppressed()).isEmpty();
    }

    private void assertAllJdbcResourcesClosed() throws SQLException {
        verify(rows).close();
        verify(statement).close();
        verify(connection).close();
    }
}
