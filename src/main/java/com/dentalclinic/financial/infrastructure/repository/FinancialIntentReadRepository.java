package com.dentalclinic.financial.infrastructure.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.financial.domain.FinancialIntentDisposition;
import com.dentalclinic.financial.domain.FinancialIntentOperation;

/** Una sentencia por pagina/detalle: intencion y recibo del MISMO snapshot READ_COMMITTED.
 * No lee command_body, no toma locks de escritura ni crea recibos/efectos. */
@Repository
@Transactional(propagation = Propagation.MANDATORY, readOnly = true)
public class FinancialIntentReadRepository {
    private static final String SELECT = "SELECT i.operation, i.idempotency_key, i.command_version, i.hash_version, "
            + "i.request_hash, i.disposition, i.registered_at, i.closed_at, i.rejected_attempt_at, "
            + "i.rejection_code, i.rejection_http_status, h.id AS receipt_id, h.request_hash AS receipt_hash, "
            + "h.response_status, h.response_body::text AS receipt_body FROM financial_intent i "
            + "LEFT JOIN http_idempotency h ON h.actor_user_id = i.actor_user_id "
            + "AND h.operation = i.operation AND h.idempotency_key = i.idempotency_key ";
    private final JdbcTemplate jdbc;

    public FinancialIntentReadRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<Row> find(Long actor, FinancialIntentOperation operation, String key) {
        return jdbc.query(SELECT + "WHERE i.actor_user_id = ? AND i.operation = ? AND i.idempotency_key = ?",
                FinancialIntentReadRepository::read, actor, operation.name(), key).stream().findFirst();
    }

    public List<Row> page(Long actor, boolean correctionsAllowed, boolean pending, Position before, int limit) {
        String sql = SELECT + "WHERE i.actor_user_id = ? ";
        var args = new ArrayList<Object>();
        args.add(actor);
        if (!correctionsAllowed) {
            sql += "AND i.operation IN ('FIN_CONFIRM_CHARGE', 'FIN_REGISTER_PAYMENT') ";
        }
        if (pending) sql += "AND i.disposition = 'OPEN' AND h.id IS NULL ";
        if (before != null) {
            sql += "AND (i.registered_at, i.idempotency_key, i.operation) < (?, ?, ?) ";
            args.add(java.sql.Timestamp.from(before.at()));
            args.add(before.key());
            args.add(before.operation().name());
        }
        sql += "ORDER BY i.registered_at DESC, i.idempotency_key DESC, i.operation DESC LIMIT ?";
        args.add(limit);
        return jdbc.query(sql, FinancialIntentReadRepository::read, args.toArray());
    }

    public record Position(Instant at, String key, FinancialIntentOperation operation) { }

    /** Solo interna; hashes y JSON de recibo nunca se devuelven desde un controller. */
    public record Row(FinancialIntentOperation operation, String key, int commandVersion, int hashVersion,
            String hash, FinancialIntentDisposition disposition, Instant registeredAt, Instant closedAt,
            Instant attemptedAt, String rejectionCode, Integer rejectionStatus, Long receiptId,
            String receiptHash, Integer receiptStatus, String receiptBody) {
        public Position position() { return new Position(registeredAt, key, operation); }
        @Override public String toString() { return "FinancialIntentReadRow[operation=" + operation + "]"; }
    }

    private static Row read(ResultSet rs, int index) throws SQLException {
        return new Row(FinancialIntentOperation.valueOf(rs.getString("operation")), rs.getString("idempotency_key"),
                rs.getInt("command_version"), rs.getInt("hash_version"), rs.getString("request_hash"),
                FinancialIntentDisposition.valueOf(rs.getString("disposition")), instant(rs, "registered_at"),
                instant(rs, "closed_at"), instant(rs, "rejected_attempt_at"), rs.getString("rejection_code"),
                rs.getObject("rejection_http_status", Integer.class), rs.getObject("receipt_id", Long.class),
                rs.getString("receipt_hash"), rs.getObject("response_status", Integer.class), rs.getString("receipt_body"));
    }

    private static Instant instant(ResultSet rs, String name) throws SQLException {
        var time = rs.getTimestamp(name);
        return time == null ? null : time.toInstant();
    }
}
