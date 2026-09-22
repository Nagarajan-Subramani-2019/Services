package com.oxygenraj.userinfo.repository;

import com.oxygenraj.userinfo.domain.UserAccount;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {
    private static final String COLUMNS = "ID, USERNAME, PASSWORD_HASH, EMAIL, PHONE_NUMBER, USER_ROLE, ENABLED, CREATED_AT, MODIFIED_AT";
    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<UserAccount> findByUsername(String username) {
        return jdbc.query("SELECT " + COLUMNS + " FROM USER_INFO_SCHEMA.USER_DETAILS WHERE USERNAME = ?",
                UserRepository::map, username).stream().findFirst();
    }

    public Optional<UserAccount> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM USER_INFO_SCHEMA.USER_DETAILS WHERE ID = ?",
                UserRepository::map, id).stream().findFirst();
    }

    public UserAccount create(String username, String hash, String email, String phoneNumber) {
        jdbc.update("""
                INSERT INTO USER_INFO_SCHEMA.USER_DETAILS
                    (USERNAME, PASSWORD_HASH, EMAIL, PHONE_NUMBER, USER_ROLE, ENABLED)
                VALUES (?, ?, ?, ?, 'USER', 1)
                """, username, hash, email, phoneNumber);
        return findByUsername(username).orElseThrow(() -> new IllegalStateException("Created user was not found"));
    }

    public List<UserAccount> findPage(int page, int size) {
        return jdbc.query("SELECT " + COLUMNS + " FROM USER_INFO_SCHEMA.USER_DETAILS ORDER BY ID OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                UserRepository::map, (long) page * size, size);
    }

    public long count() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM USER_INFO_SCHEMA.USER_DETAILS", Long.class);
        return count == null ? 0 : count;
    }

    private static UserAccount map(ResultSet row, int number) throws SQLException {
        return new UserAccount(row.getLong("ID"), row.getString("USERNAME"), row.getString("PASSWORD_HASH"),
                row.getString("EMAIL"), row.getString("PHONE_NUMBER"), row.getString("USER_ROLE"),
                row.getInt("ENABLED") == 1, row.getObject("CREATED_AT", OffsetDateTime.class),
                row.getObject("MODIFIED_AT", OffsetDateTime.class));
    }
}
