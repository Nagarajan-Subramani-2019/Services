package com.oxygenraj.userinfo.config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;

/** Performs one bounded, read-only startup lookup without changing schema objects. */
class OraclePortReader {

    static final String PORT_QUERY = """
            SELECT "VALUE",
                   SYS_CONTEXT('USERENV', 'SESSION_USER') AS SESSION_USER,
                   SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') AS CURRENT_SCHEMA
              FROM USER_INFO_SCHEMA.PROPERTIES
             WHERE APPLICATION = ? AND PROFILE = ? AND LABEL = ? AND "KEY" = ?
            """;

    private final ConnectionFactory connections;

    OraclePortReader() {
        this(OraclePortReader::openOracleConnection);
    }

    OraclePortReader(ConnectionFactory connections) {
        this.connections = connections;
    }

    int readPort(String url, String password) {
        Properties properties = new Properties();
        properties.setProperty("user", "USER_INFO_SCHEMA");
        properties.setProperty("password", password);
        properties.setProperty("oracle.net.CONNECT_TIMEOUT", "5000");
        properties.setProperty("oracle.jdbc.ReadTimeout", "10000");

        try (Connection connection = connections.open(url, properties);
             PreparedStatement statement = connection.prepareStatement(PORT_QUERY)) {
            statement.setQueryTimeout(5);
            statement.setString(1, "userInfoServices");
            statement.setString(2, "jdbc");
            statement.setString(3, "jdbc");
            statement.setString(4, "server.port");
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new PortConfigurationException(
                            "Missing USER_INFO_SCHEMA.PROPERTIES row: userInfoServices / jdbc / jdbc / server.port. "
                            + "Run the supplied database scripts before starting userInfoServices.");
                }
                if (!"USER_INFO_SCHEMA".equals(rows.getString("SESSION_USER"))
                        || !"USER_INFO_SCHEMA".equals(rows.getString("CURRENT_SCHEMA"))) {
                    throw new PortConfigurationException(
                            "The startup port query must run as USER_INFO_SCHEMA in USER_INFO_SCHEMA.");
                }
                String value = rows.getString("VALUE");
                if (rows.next()) {
                    throw new PortConfigurationException(
                            "Multiple server.port rows found for userInfoServices / jdbc / jdbc; expected exactly one.");
                }
                return parsePort(value);
            }
        }
        catch (SQLException exception) {
            // JDBC messages can disclose credentials, so retain neither cause nor suppressed exceptions.
            throw new IllegalStateException(
                    "Cannot read the startup port from USER_INFO_SCHEMA.PROPERTIES (database error code "
                    + exception.getErrorCode() + "). Check the tunnel, database credentials, table and SELECT access.");
        }
        catch (PortConfigurationException exception) {
            // Resource cleanup can attach sensitive suppressed JDBC exceptions to validation failures.
            throw new IllegalStateException(exception.getMessage());
        }
    }

    private static Connection openOracleConnection(String url, Properties properties) throws SQLException {
        try {
            // External containers may initialize DriverManager before loading this application.
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
                "USER_INFO_SCHEMA.PROPERTIES server.port must be an integer from 1 to 65535.");
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
