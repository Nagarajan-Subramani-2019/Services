package com.oxygenraj.userinfo.config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;

/** Reads the existing discovery-server setting using the shared properties table's EUREKA_DB owner account. */
class OracleEurekaPortReader {

    static final String PORT_QUERY = """
            SELECT "VALUE",
                   SYS_CONTEXT('USERENV', 'SESSION_USER') AS SESSION_USER,
                   SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') AS CURRENT_SCHEMA,
                   SYS_CONTEXT('USERENV', 'CON_NAME') AS CON_NAME
              FROM EUREKA_DB.PROPERTIES
             WHERE APPLICATION = ? AND PROFILE = ? AND LABEL = ? AND "KEY" = ?
            """;

    private final ConnectionFactory connections;

    OracleEurekaPortReader() {
        this(OracleEurekaPortReader::openOracleConnection);
    }

    OracleEurekaPortReader(ConnectionFactory connections) {
        this.connections = connections;
    }

    int readPort(String url, String password) {
        Properties properties = new Properties();
        properties.setProperty("user", "EUREKA_DB");
        properties.setProperty("password", password);
        properties.setProperty("oracle.net.CONNECT_TIMEOUT", "5000");
        properties.setProperty("oracle.jdbc.ReadTimeout", "10000");

        try (Connection connection = connections.open(url, properties);
             PreparedStatement statement = connection.prepareStatement(PORT_QUERY)) {
            statement.setQueryTimeout(5);
            statement.setString(1, "eureka-server");
            statement.setString(2, "jdbc");
            statement.setString(3, "jdbc");
            statement.setString(4, "server.port");
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new PortConfigurationException(
                            "Missing EUREKA_DB.PROPERTIES row: eureka-server / jdbc / jdbc / server.port. "
                            + "Have the Eureka database owner provide this setting before startup.");
                }
                if (!"EUREKA_DB".equals(rows.getString("SESSION_USER"))
                        || !"EUREKA_DB".equals(rows.getString("CURRENT_SCHEMA"))
                        || !"FREEPDB1".equals(rows.getString("CON_NAME"))) {
                    throw new PortConfigurationException(
                            "The Eureka port query must run as EUREKA_DB in EUREKA_DB within FREEPDB1.");
                }
                String value = rows.getString("VALUE");
                if (rows.next()) {
                    throw new PortConfigurationException(
                            "Multiple server.port rows found for eureka-server / jdbc / jdbc; expected exactly one.");
                }
                return parsePort(value);
            }
        }
        catch (SQLException exception) {
            // Driver messages and cleanup failures may contain secrets; expose only the numeric error code.
            throw new IllegalStateException("Cannot read the Eureka server port from EUREKA_DB.PROPERTIES "
                    + "(database error code " + exception.getErrorCode() + "). Check the tunnel, EUREKA_DB "
                    + "credentials, EUREKA_DB.PROPERTIES table and FREEPDB1 PDB.");
        }
        catch (PortConfigurationException exception) {
            // Do not retain sensitive suppressed exceptions from resource cleanup.
            throw new IllegalStateException(exception.getMessage());
        }
    }

    private static Connection openOracleConnection(String url, Properties properties) throws SQLException {
        try {
            Class.forName("oracle.jdbc.OracleDriver");
        }
        catch (ClassNotFoundException exception) {
            throw new IllegalStateException("The Oracle JDBC driver is missing from the application WAR.");
        }
        return DriverManager.getConnection(url, properties);
    }

    private static int parsePort(String value) {
        if (value == null || !value.trim().matches("[0-9]{1,5}")) {
            throw invalidPort();
        }
        int port = Integer.parseInt(value.trim());
        if (port < 1 || port > 65535) {
            throw invalidPort();
        }
        return port;
    }

    private static PortConfigurationException invalidPort() {
        return new PortConfigurationException(
                "EUREKA_DB.PROPERTIES server.port must be an integer from 1 to 65535.");
    }

    private static final class PortConfigurationException extends IllegalStateException {
        PortConfigurationException(String message) {
            super(message);
        }
    }

    @FunctionalInterface
    interface ConnectionFactory {
        Connection open(String url, Properties properties) throws SQLException;
    }
}
