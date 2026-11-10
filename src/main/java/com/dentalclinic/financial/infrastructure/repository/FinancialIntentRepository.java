package com.dentalclinic.financial.infrastructure.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.financial.domain.FinancialIntent;
import com.dentalclinic.financial.domain.FinancialIntentDisposition;
import com.dentalclinic.financial.domain.FinancialIntentOperation;

/**
 * Insercion/lectura y cierres condicionales B3: nunca modifica comando/identidad ni borra.
 * JDBC evita exponer una entidad mutable o un JSONB convertido a Double. Comparte la
 * conexion transaccional JPA del servicio. No consulta tablas al arrancar.
 * El servicio aplica gate y advisory lock ANTES de invocar este repositorio interno.
 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class FinancialIntentRepository {
    private final JdbcTemplate jdbc;

    public FinancialIntentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<FinancialIntent> findByIdentityForUpdate(Long actorUserId,
            FinancialIntentOperation operation, String key) {
        return jdbc.query("SELECT * FROM financial_intent WHERE actor_user_id = ? "
                        + "AND operation = ? AND idempotency_key = ? FOR UPDATE",
                FinancialIntentRepository::read, actorUserId, operation.name(), key).stream().findFirst();
    }

    /** La PK es la ultima barrera incluso frente a escritores que omitan el advisory lock. */
    public boolean insertOpen(Long actorUserId, FinancialIntentOperation operation, String key,
            int commandVersion, int hashVersion, String hash, String commandJson) {
        return jdbc.update("INSERT INTO financial_intent (actor_user_id, operation, idempotency_key, "
                        + "command_version, hash_version, request_hash, command_body) "
                        + "VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb)) "
                        + "ON CONFLICT (actor_user_id, operation, idempotency_key) DO NOTHING",
                actorUserId, operation.name(), key, commandVersion, hashVersion, hash, commandJson) == 1;
    }

    /** Instante de BD de la transaccion de ejecucion; coherente con registered_at/closed_at de V021. */
    public Instant databaseTime() {
        return jdbc.queryForObject("SELECT clock_timestamp()", java.sql.Timestamp.class).toInstant();
    }

    /** Solo metadata terminal; el llamador debe tener advisory lock y fila bloqueada. */
    public boolean closeOpen(FinancialIntent intent, FinancialIntentDisposition disposition,
            Instant rejectedAttemptAt, String rejectionCode, Integer rejectionStatus) {
        if (disposition == FinancialIntentDisposition.OPEN) throw new IllegalArgumentException("Cierre obligatorio.");
        return jdbc.update("UPDATE financial_intent SET disposition = ?, closed_at = clock_timestamp(), "
                        + "rejected_attempt_at = ?, rejection_code = ?, rejection_http_status = ? "
                        + "WHERE actor_user_id = ? AND operation = ? AND idempotency_key = ? "
                        + "AND disposition = 'OPEN' AND request_hash = ? AND command_version = ? AND hash_version = ? "
                        + "AND NOT EXISTS (SELECT 1 FROM http_idempotency h WHERE h.actor_user_id = financial_intent.actor_user_id "
                        + "AND h.operation = financial_intent.operation AND h.idempotency_key = financial_intent.idempotency_key)",
                disposition.name(), rejectedAttemptAt == null ? null : java.sql.Timestamp.from(rejectedAttemptAt),
                rejectionCode, rejectionStatus, intent.actorUserId(), intent.operation().name(), intent.idempotencyKey(),
                intent.requestHash(), intent.commandVersion(), intent.hashVersion()) == 1;
    }

    private static FinancialIntent read(ResultSet rs, int row) throws SQLException {
        return new FinancialIntent(rs.getLong("actor_user_id"),
                FinancialIntentOperation.valueOf(rs.getString("operation")),
                rs.getString("idempotency_key"), rs.getInt("command_version"), rs.getInt("hash_version"),
                rs.getString("request_hash"), rs.getString("command_body"),
                FinancialIntentDisposition.valueOf(rs.getString("disposition")),
                instant(rs, "registered_at"), instant(rs, "closed_at"), instant(rs, "rejected_attempt_at"),
                rs.getString("rejection_code"), rs.getObject("rejection_http_status", Integer.class),
                instant(rs, "command_purged_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
