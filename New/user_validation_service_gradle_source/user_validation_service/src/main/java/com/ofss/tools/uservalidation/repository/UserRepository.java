package com.ofss.tools.uservalidation.repository;

import com.ofss.tools.uservalidation.api.ApiModels.LoginStatistics;
import com.ofss.tools.uservalidation.api.ApiModels.OverallStatistics;
import com.ofss.tools.uservalidation.api.ApiModels.UserResponse;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {
    private static final String USER_SELECT = """
            SELECT c.USER_ID, c.USERNAME, d.FULL_NAME, d.ADDRESS, c.RECORD_STATUS,
                   c.CREATED_AT, c.UPDATED_AT, c.LAST_LOGIN_AT
              FROM UV_USER_CREDENTIALS c JOIN UV_USER_DETAILS d ON d.USER_ID = c.USER_ID
            """;
    private static final String LOGIN_SUMMARY = """
            SELECT COUNT(CASE WHEN OUTCOME = 'SUCCESS' THEN 1 END) TOTAL_LOGGED_IN,
                   COUNT(CASE WHEN OUTCOME = 'FAILED' THEN 1 END) TOTAL_FAILED_LOGIN,
                   COUNT(*) TOTAL_ATTEMPTS,
                   NVL(ROUND(AVG(PROCESSING_TIME_MS), 3), 0) AVERAGE_TIME_MS
              FROM UV_LOGIN_STATISTICS
            """;
    private final JdbcTemplate jdbc;
    private final SimpleJdbcInsert insertUser;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        insertUser = new SimpleJdbcInsert(jdbc).withTableName("UV_USER_CREDENTIALS")
                .usingColumns("USERNAME", "PASSWORD_HASH", "RECORD_STATUS")
                .usingGeneratedKeyColumns("USER_ID").withoutTableColumnMetaDataAccess();
    }

    public long create(String username, String passwordHash, String name, String address) {
        Map<String, Object> values = new HashMap<>();
        values.put("USERNAME", username);
        values.put("PASSWORD_HASH", passwordHash);
        values.put("RECORD_STATUS", "ACTIVE");
        long userId = insertUser.executeAndReturnKey(values).longValue();
        jdbc.update("INSERT INTO UV_USER_DETAILS (USER_ID, FULL_NAME, ADDRESS) VALUES (?, ?, ?)",
                userId, name, address);
        return userId;
    }

    public Optional<UserResponse> find(long userId) {
        return jdbc.query(USER_SELECT + " WHERE c.USER_ID = ?", this::mapUser, userId).stream().findFirst();
    }

    public List<UserResponse> list(String status, int page, int size) {
        return jdbc.query(USER_SELECT + """
                 WHERE (? = 'ALL' OR c.RECORD_STATUS = ?)
                 ORDER BY c.USER_ID OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
                """, this::mapUser, status, status, (long) page * size, size);
    }

    public List<Long> ids(String status) {
        return jdbc.query("""
                SELECT USER_ID FROM UV_USER_CREDENTIALS
                 WHERE (? = 'ALL' OR RECORD_STATUS = ?) ORDER BY USER_ID
                """, (result, row) -> result.getLong("USER_ID"), status, status);
    }

    public long count(String status) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM UV_USER_CREDENTIALS WHERE (? = 'ALL' OR RECORD_STATUS = ?)
                """, Long.class, status, status);
    }

    public Optional<Credentials> lockById(long userId) {
        return jdbc.query("""
                SELECT USER_ID, USERNAME, PASSWORD_HASH, RECORD_STATUS
                  FROM UV_USER_CREDENTIALS WHERE USER_ID = ? FOR UPDATE
                """, this::mapCredentials, userId).stream().findFirst();
    }

    public Optional<Credentials> lockByUsername(String username) {
        return jdbc.query("""
                SELECT USER_ID, USERNAME, PASSWORD_HASH, RECORD_STATUS
                  FROM UV_USER_CREDENTIALS WHERE USERNAME = ? FOR UPDATE
                """, this::mapCredentials, username).stream().findFirst();
    }

    public void update(long userId, String username, String passwordHash, String name, String address) {
        jdbc.update("""
                UPDATE UV_USER_CREDENTIALS
                   SET USERNAME = ?, PASSWORD_HASH = ?, UPDATED_AT = SYS_EXTRACT_UTC(SYSTIMESTAMP)
                 WHERE USER_ID = ? AND RECORD_STATUS = 'ACTIVE'
                """, username, passwordHash, userId);
        jdbc.update("""
                UPDATE UV_USER_DETAILS
                   SET FULL_NAME = ?, ADDRESS = ?, UPDATED_AT = SYS_EXTRACT_UTC(SYSTIMESTAMP)
                 WHERE USER_ID = ?
                """, name, address, userId);
    }

    public void close(long userId) {
        jdbc.update("""
                UPDATE UV_USER_CREDENTIALS
                   SET RECORD_STATUS = 'CLOSED', UPDATED_AT = SYS_EXTRACT_UTC(SYSTIMESTAMP)
                 WHERE USER_ID = ?
                """, userId);
        jdbc.update("""
                UPDATE UV_USER_DETAILS
                   SET RECORD_STATUS = 'CLOSED', UPDATED_AT = SYS_EXTRACT_UTC(SYSTIMESTAMP)
                 WHERE USER_ID = ?
                """, userId);
    }

    public void recordLogin(Long userId, String username, boolean successful, BigDecimal durationMs) {
        jdbc.update("""
                INSERT INTO UV_LOGIN_STATISTICS
                    (USER_ID, ATTEMPTED_USERNAME, OUTCOME, FAILURE_CODE, PROCESSING_TIME_MS)
                VALUES (?, ?, ?, ?, ?)
                """, new Object[] {userId, username, successful ? "SUCCESS" : "FAILED",
                    successful ? null : "INVALID_CREDENTIALS", durationMs},
                new int[] {Types.NUMERIC, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.NUMERIC});
        if (successful) {
            jdbc.update("""
                    UPDATE UV_USER_CREDENTIALS SET LAST_LOGIN_AT = SYS_EXTRACT_UTC(SYSTIMESTAMP)
                     WHERE USER_ID = ?
                    """, userId);
        }
    }

    public LoginStatistics userStatistics(long userId) {
        return jdbc.queryForObject(LOGIN_SUMMARY + " WHERE USER_ID = ?", this::mapSummary, userId);
    }

    public OverallStatistics overallStatistics() {
        LoginStatistics login = jdbc.queryForObject(LOGIN_SUMMARY, this::mapSummary);
        long distinct = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT USER_ID) FROM UV_LOGIN_STATISTICS WHERE OUTCOME = 'SUCCESS'
                """, Long.class);
        return jdbc.queryForObject("""
                SELECT COUNT(CASE WHEN RECORD_STATUS = 'ACTIVE' THEN 1 END) ACTIVE_USERS,
                       COUNT(CASE WHEN RECORD_STATUS = 'CLOSED' THEN 1 END) CLOSED_USERS,
                       COUNT(CASE WHEN LAST_LOGIN_AT IS NULL THEN 1 END) NEVER_LOGGED_IN
                  FROM UV_USER_CREDENTIALS
                """, (result, row) -> new OverallStatistics(result.getLong("ACTIVE_USERS"),
                        result.getLong("CLOSED_USERS"), result.getLong("NEVER_LOGGED_IN"), distinct, login));
    }

    public void checkDatabase() {
        jdbc.queryForObject("SELECT 1 FROM DUAL", Integer.class);
    }

    private UserResponse mapUser(ResultSet result, int row) throws SQLException {
        return new UserResponse(result.getLong("USER_ID"), result.getString("USERNAME"),
                result.getString("FULL_NAME"), result.getString("ADDRESS"), result.getString("RECORD_STATUS"),
                instant(result, "CREATED_AT"), instant(result, "UPDATED_AT"), instant(result, "LAST_LOGIN_AT"));
    }

    private Credentials mapCredentials(ResultSet result, int row) throws SQLException {
        return new Credentials(result.getLong("USER_ID"), result.getString("USERNAME"),
                result.getString("PASSWORD_HASH"), result.getString("RECORD_STATUS"));
    }

    private LoginStatistics mapSummary(ResultSet result, int row) throws SQLException {
        return new LoginStatistics(result.getLong("TOTAL_LOGGED_IN"), result.getLong("TOTAL_FAILED_LOGIN"),
                result.getLong("TOTAL_ATTEMPTS"), result.getBigDecimal("AVERAGE_TIME_MS"));
    }

    private Instant instant(ResultSet result, String column) throws SQLException {
        LocalDateTime value = result.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    public record Credentials(long userId, String username, String passwordHash, String status) {
        @Override
        public String toString() {
            return "Credentials[userId=" + userId + ", redacted]";
        }
    }
}
