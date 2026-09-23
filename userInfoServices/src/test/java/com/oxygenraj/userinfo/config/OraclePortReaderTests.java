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

    private static final String URL = "jdbc:oracle:thin:@//database.example.test:1521/FREEPDB1";
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
        when(rows.getString("SESSION_USER")).thenReturn("EUREKA_DB");
        when(rows.getString("CURRENT_SCHEMA")).thenReturn("EUREKA_DB");
        when(rows.getString("CON_NAME")).thenReturn("FREEPDB1");
        when(rows.getString("VALUE")).thenReturn("8082");
    }

    @Test
    void usesTheFixedSchemaAndExactFiltersWithBoundedJdbcTimeouts() throws SQLException {
        assertThat(reader.readPort(URL, PASSWORD)).isEqualTo(8082);

        ArgumentCaptor<Properties> properties = ArgumentCaptor.forClass(Properties.class);
        verify(connections).open(eq(URL), properties.capture());
        assertThat(properties.getValue()).containsOnlyKeys(
                "user", "password", "oracle.net.CONNECT_TIMEOUT", "oracle.jdbc.ReadTimeout");
        assertThat(properties.getValue().getProperty("user")).isEqualTo("EUREKA_DB");
        assertThat(properties.getValue().getProperty("password")).isEqualTo(PASSWORD);
        assertThat(properties.getValue().getProperty("oracle.net.CONNECT_TIMEOUT")).isEqualTo("5000");
        assertThat(properties.getValue().getProperty("oracle.jdbc.ReadTimeout")).isEqualTo("10000");
        assertThat(OraclePortReader.PORT_QUERY).contains("FROM EUREKA_DB.PROPERTIES")
                .contains("SESSION_USER", "CURRENT_SCHEMA", "CON_NAME")
                .doesNotContain("ALTER", "CREATE", "GRANT", PASSWORD, URL);
        verify(connection).prepareStatement(OraclePortReader.PORT_QUERY);
        verify(statement).setQueryTimeout(5);
        verify(statement).setString(1, "userInfoServices");
        verify(statement).setString(2, "jdbc");
        verify(statement).setString(3, "jdbc");
        verify(statement).setString(4, "server.port");
        assertAllJdbcResourcesClosed();
    }

    @Test
    void ownAndEurekaPortsComeFromTheSameTableWithDifferentApplicationSelectors() throws SQLException {
        when(rows.getString("VALUE")).thenReturn("8770");
        OracleEurekaPortReader.ConnectionFactory eurekaConnections =
                mock(OracleEurekaPortReader.ConnectionFactory.class);
        Connection eurekaConnection = mock(Connection.class);
        PreparedStatement eurekaStatement = mock(PreparedStatement.class);
        ResultSet eurekaRows = mock(ResultSet.class);
        when(eurekaConnections.open(anyString(), any(Properties.class))).thenReturn(eurekaConnection);
        when(eurekaConnection.prepareStatement(OracleEurekaPortReader.PORT_QUERY)).thenReturn(eurekaStatement);
        when(eurekaStatement.executeQuery()).thenReturn(eurekaRows);
        when(eurekaRows.next()).thenReturn(true, false);
        when(eurekaRows.getString("SESSION_USER")).thenReturn("EUREKA_DB");
        when(eurekaRows.getString("CURRENT_SCHEMA")).thenReturn("EUREKA_DB");
        when(eurekaRows.getString("CON_NAME")).thenReturn("FREEPDB1");
        when(eurekaRows.getString("VALUE")).thenReturn("8760");

        assertThat(reader.readPort(URL, PASSWORD)).isEqualTo(8770);
        assertThat(new OracleEurekaPortReader(eurekaConnections).readPort(URL, PASSWORD)).isEqualTo(8760);

        assertThat(OraclePortReader.PORT_QUERY).contains("FROM EUREKA_DB.PROPERTIES")
                .contains("WHERE APPLICATION = ? AND PROFILE = ? AND LABEL = ? AND \"KEY\" = ?")
                .doesNotContain("USER_INFO_SCHEMA.PROPERTIES");
        assertThat(OracleEurekaPortReader.PORT_QUERY).contains("FROM EUREKA_DB.PROPERTIES")
                .contains("WHERE APPLICATION = ? AND PROFILE = ? AND LABEL = ? AND \"KEY\" = ?");
        verify(statement).setString(1, "userInfoServices");
        verify(eurekaStatement).setString(1, "eureka-server");
        verify(eurekaStatement).setString(2, "jdbc");
        verify(eurekaStatement).setString(3, "jdbc");
        verify(eurekaStatement).setString(4, "server.port");
        ArgumentCaptor<Properties> ownCredentials = ArgumentCaptor.forClass(Properties.class);
        ArgumentCaptor<Properties> eurekaCredentials = ArgumentCaptor.forClass(Properties.class);
        verify(connections).open(eq(URL), ownCredentials.capture());
        verify(eurekaConnections).open(eq(URL), eurekaCredentials.capture());
        assertThat(ownCredentials.getValue()).isEqualTo(eurekaCredentials.getValue());
        assertThat(ownCredentials.getValue().getProperty("user")).isEqualTo("EUREKA_DB");
        assertThat(ownCredentials.getValue().getProperty("password")).isEqualTo(PASSWORD);
        assertAllJdbcResourcesClosed();
        verify(eurekaRows).close();
        verify(eurekaStatement).close();
        verify(eurekaConnection).close();
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
        assertThat(failure).hasMessageContaining("Missing EUREKA_DB.PROPERTIES row")
                .hasMessageContaining("userInfoServices / jdbc / jdbc / server.port");
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
            "USER_INFO_SCHEMA, USER_INFO_SCHEMA, FREEPDB1",
            "USER_INFO_SCHEMA, EUREKA_DB, FREEPDB1",
            "EUREKA_DB, USER_INFO_SCHEMA, FREEPDB1",
            "SYSTEM, EUREKA_DB, FREEPDB1",
            "EUREKA_DB, SYSTEM, FREEPDB1",
            "eureka_db, EUREKA_DB, FREEPDB1",
            "EUREKA_DB, eureka_db, FREEPDB1",
            "EUREKA_DB, EUREKA_DB, CDB$ROOT",
            "EUREKA_DB, EUREKA_DB, OTHERPDB",
            "EUREKA_DB, EUREKA_DB, freepdb1"
    })
    void rejectsWrongSessionUserSchemaOrPdb(String sessionUser, String schema, String pdb) throws SQLException {
        when(rows.getString("SESSION_USER")).thenReturn(sessionUser);
        when(rows.getString("CURRENT_SCHEMA")).thenReturn(schema);
        when(rows.getString("CON_NAME")).thenReturn(pdb);

        Throwable failure = catchThrowable(() -> reader.readPort(URL, PASSWORD));

        assertSanitized(failure);
        assertThat(failure).hasMessageContaining("must run as EUREKA_DB in EUREKA_DB within FREEPDB1");
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

    @ParameterizedTest
    @ValueSource(ints = {942, 1031})
    void missingSharedTableOrAccessFailsWithSanitizedPropertiesDatabaseInstructions(int errorCode)
            throws SQLException {
        when(statement.executeQuery()).thenThrow(new SQLException(
                "Connection details: " + URL + " password=" + PASSWORD, "08006", errorCode));

        Throwable failure = catchThrowable(() -> reader.readPort(URL, PASSWORD));

        assertSanitized(failure);
        assertThat(failure).hasMessageContaining(Integer.toString(errorCode))
                .hasMessageContaining("EUREKA_DB credentials")
                .hasMessageContaining("EUREKA_DB.PROPERTIES table and FREEPDB1 PDB")
                .hasMessageNotContaining("grant USER_INFO_SCHEMA");
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
        assertThat(failure.getMessage()).doesNotContain(PASSWORD, URL, "Connection details:", "OTHERPDB", "CDB$ROOT");
        assertThat(failure.getSuppressed()).isEmpty();
    }

    private void assertAllJdbcResourcesClosed() throws SQLException {
        verify(rows).close();
        verify(statement).close();
        verify(connection).close();
    }
}
