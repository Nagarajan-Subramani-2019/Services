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
        when(rows.getString("SESSION_USER")).thenReturn("USER_INFO_SCHEMA");
        when(rows.getString("CURRENT_SCHEMA")).thenReturn("USER_INFO_SCHEMA");
        when(rows.getString("CON_NAME")).thenReturn("FREEPDB1");
        when(rows.getString("VALUE")).thenReturn("8760");
    }

    @Test
    void readsTheExactExistingEurekaSettingUsingTheApplicationAccountAndBoundedTimeouts() throws SQLException {
        assertThat(reader.readPort(URL, PASSWORD)).isEqualTo(8760);

        ArgumentCaptor<Properties> properties = ArgumentCaptor.forClass(Properties.class);
        verify(connections).open(eq(URL), properties.capture());
        assertThat(properties.getValue()).containsOnlyKeys(
                "user", "password", "oracle.net.CONNECT_TIMEOUT", "oracle.jdbc.ReadTimeout");
        assertThat(properties.getValue().getProperty("user")).isEqualTo("USER_INFO_SCHEMA");
        assertThat(properties.getValue().getProperty("password")).isEqualTo(PASSWORD);
        assertThat(properties.getValue().getProperty("oracle.net.CONNECT_TIMEOUT")).isEqualTo("5000");
        assertThat(properties.getValue().getProperty("oracle.jdbc.ReadTimeout")).isEqualTo("10000");
        assertThat(OracleEurekaPortReader.PORT_QUERY.strip()).startsWith("SELECT ")
                .contains("FROM EUREKA_DB.PROPERTIES", "SESSION_USER", "CURRENT_SCHEMA", "CON_NAME")
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
            "EUREKA_DB, USER_INFO_SCHEMA, FREEPDB1",
            "SYSTEM, USER_INFO_SCHEMA, FREEPDB1",
            "USER_INFO_SCHEMA, EUREKA_DB, FREEPDB1",
            "USER_INFO_SCHEMA, SYSTEM, FREEPDB1",
            "user_info_schema, USER_INFO_SCHEMA, FREEPDB1",
            "USER_INFO_SCHEMA, USER_INFO_SCHEMA, CDB$ROOT",
            "USER_INFO_SCHEMA, USER_INFO_SCHEMA, OTHERPDB",
            "USER_INFO_SCHEMA, USER_INFO_SCHEMA, freepdb1"
    })
    void rejectsWrongSessionUserSchemaOrPdb(String sessionUser, String schema, String pdb) throws SQLException {
        when(rows.getString("SESSION_USER")).thenReturn(sessionUser);
        when(rows.getString("CURRENT_SCHEMA")).thenReturn(schema);
        when(rows.getString("CON_NAME")).thenReturn(pdb);

        Throwable failure = catchThrowable(() -> reader.readPort(URL, PASSWORD));

        assertSanitized(failure);
        assertThat(failure).hasMessageContaining("USER_INFO_SCHEMA in USER_INFO_SCHEMA within FREEPDB1");
        assertAllJdbcResourcesClosed();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SESSION_USER", "CURRENT_SCHEMA", "CON_NAME"})
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
    void missingTableOrSelectAccessReportsExternalSetupWithoutExposingJdbcDetails(int errorCode) throws SQLException {
        when(statement.executeQuery()).thenThrow(sensitiveSqlException(errorCode));

        Throwable failure = catchThrowable(() -> reader.readPort(URL, PASSWORD));

        assertSanitized(failure);
        assertThat(failure).hasMessageContaining(Integer.toString(errorCode))
                .hasMessageContaining("grant USER_INFO_SCHEMA SELECT access to EUREKA_DB.PROPERTIES externally");
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
