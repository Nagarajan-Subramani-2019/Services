package com.oxygenraj.transact.repository;

import com.oxygenraj.transact.domain.Transaction;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TransactionRepository {
    private static final String TABLE = "USER_TRANSACT_SCHEMA.\"TRANSACTION\"";
    private static final String COLUMNS = "ID, USER_ID, MONTH_NAME, MONTH_COUNT, AMOUNT, CREATED_AT, MODIFIED_AT, VERSION";
    private final JdbcTemplate jdbc;

    public TransactionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Transaction create(long userId, String monthName, int monthCount, BigDecimal amount) {
        Long id = jdbc.queryForObject("SELECT USER_TRANSACT_SCHEMA.TRANSACTION_ID_SEQ.NEXTVAL FROM DUAL", Long.class);
        if (id == null) {
            throw new DataRetrievalFailureException("A transaction identifier could not be allocated");
        }
        jdbc.update("INSERT INTO " + TABLE + " (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, SYSTIMESTAMP, SYSTIMESTAMP, 0)",
                id, userId, monthName, monthCount, amount);
        return findById(id).orElseThrow(() -> new DataRetrievalFailureException("The created transaction could not be read"));
    }

    public Optional<Transaction> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM " + TABLE + " WHERE ID = ?",
                TransactionRepository::map, id).stream().findFirst();
    }

    public List<Transaction> findPage(int page, int size, Long userId) {
        long offset = (long) page * size;
        String select = "SELECT " + COLUMNS + " FROM " + TABLE;
        String paging = " ORDER BY ID OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";
        if (userId == null) {
            return jdbc.query(select + paging, TransactionRepository::map, offset, size);
        }
        return jdbc.query(select + " WHERE USER_ID = ?" + paging,
                TransactionRepository::map, userId, offset, size);
    }

    public long count(Long userId) {
        String select = "SELECT COUNT(*) FROM " + TABLE;
        Long count = userId == null
                ? jdbc.queryForObject(select, Long.class)
                : jdbc.queryForObject(select + " WHERE USER_ID = ?", Long.class, userId);
        return count == null ? 0 : count;
    }

    /** The version predicate protects the complete replacement from stale clients. */
    public int update(long id, long version, long userId, String monthName, int monthCount, BigDecimal amount) {
        return jdbc.update("UPDATE " + TABLE + " SET USER_ID = ?, MONTH_NAME = ?, MONTH_COUNT = ?, AMOUNT = ?, "
                        + "MODIFIED_AT = SYSTIMESTAMP, VERSION = VERSION + 1 WHERE ID = ? AND VERSION = ?",
                userId, monthName, monthCount, amount, id, version);
    }

    private static Transaction map(ResultSet row, int rowNumber) throws SQLException {
        return new Transaction(row.getLong("ID"), row.getLong("USER_ID"), row.getString("MONTH_NAME"),
                row.getInt("MONTH_COUNT"), row.getBigDecimal("AMOUNT"),
                row.getObject("CREATED_AT", OffsetDateTime.class), row.getObject("MODIFIED_AT", OffsetDateTime.class),
                row.getLong("VERSION"));
    }
}
