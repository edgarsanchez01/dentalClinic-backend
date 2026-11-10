package com.dentalclinic.checkout.infrastructure.repository;

import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.dentalclinic.checkout.domain.AppointmentCheckout;

/**
 * Lector JDBC de la identidad historica del cierre; V020 es opcional.
 *
 * <p>No forma parte del metamodelo JPA. No crea esquema, no modifica datos y no expone borrados.
 * {@code save} conserva la firma anterior solo para rechazar expresamente cualquier escritor.
 */
@Repository
public class AppointmentCheckoutRepository {
    private final JdbcTemplate jdbc;

    public AppointmentCheckoutRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Retired write compatibility: always fails before touching the database. */
    public AppointmentCheckout save(AppointmentCheckout checkout) {
        throw new com.dentalclinic.identity.domain.exception.ForbiddenOperationException(
                "El repositorio historico de checkout es de solo lectura.");
    }

    public Optional<AppointmentCheckout> findById(Long id) { return read("id", id); }

    public Optional<AppointmentCheckout> findByAppointmentId(Long appointmentId) {
        return read("appointment_id", appointmentId);
    }

    private Optional<AppointmentCheckout> read(String column, Long id) {
        // Checking catalog presence avoids an undefined-table error aborting the caller transaction.
        // No DDL, fallback data, or negative-presence cache. V020 remains historical and optional.
        if (!Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT to_regclass('appointment_checkout') IS NOT NULL", Boolean.class))) return Optional.empty();
        return jdbc.query("SELECT * FROM appointment_checkout WHERE " + column + " = ?", (rs, n) ->
                AppointmentCheckout.historical(rs.getLong("id"), rs.getLong("appointment_id"),
                        rs.getInt("occurrence_version"), rs.getLong("charge_id"),
                        rs.getObject("payment_movement_id", Long.class),
                        com.dentalclinic.checkout.domain.ChargeDisposition.valueOf(rs.getString("charge_disposition")),
                        rs.getLong("actor_user_id"), rs.getTimestamp("completed_at").toInstant()), id)
                .stream().findFirst();
    }
}
