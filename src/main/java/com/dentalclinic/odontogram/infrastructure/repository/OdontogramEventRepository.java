package com.dentalclinic.odontogram.infrastructure.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import com.dentalclinic.appointment.domain.AppointmentStatus;
import com.dentalclinic.appointment.domain.RecordedAppointmentContext;
import com.dentalclinic.odontogram.domain.*;

/** One SQL stream: filter/count/order/page the UNION, not five independently paginated streams. */
@Repository
public class OdontogramEventRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public OdontogramEventRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public static final String STREAM_SQL = String.join(" UNION ALL ",
            select("tooth_history", "CONDITION_TOOTH", 1, false,
                    "h.previous_condition::text,h.new_condition::text,NULL::text AS previous_notes,NULL::text AS new_notes,NULL::bigint AS treatment_id,NULL::text AS change_kind"),
            select("tooth_notes_history", "NOTES_TOOTH", 2, false,
                    "NULL::text AS previous_condition,NULL::text AS new_condition,h.previous_notes,h.new_notes,NULL::bigint AS treatment_id,NULL::text AS change_kind"),
            select("tooth_treatment_history", "TREATMENT_LINK", 3, false,
                    "NULL::text AS previous_condition,NULL::text AS new_condition,NULL::text AS previous_notes,NULL::text AS new_notes,h.treatment_id,h.change_kind::text"),
            select("tooth_surface_history", "CONDITION_SURFACE", 4, true,
                    "h.previous_condition::text,h.new_condition::text,NULL::text AS previous_notes,NULL::text AS new_notes,NULL::bigint AS treatment_id,NULL::text AS change_kind"),
            select("tooth_surface_notes_history", "NOTES_SURFACE", 5, true,
                    "NULL::text AS previous_condition,NULL::text AS new_condition,h.previous_notes,h.new_notes,NULL::bigint AS treatment_id,NULL::text AS change_kind"));

    private static String select(String table, String source, int rank, boolean surface, String payload) {
        return "SELECT h.id,t.id AS tooth_record_id,"
                + (surface ? "s.id AS surface_record_id,s.surface::text" : "NULL::bigint AS surface_record_id,NULL::text AS surface")
                + ",t.tooth_fdi,t.patient_id,'" + source + "'::text AS source," + rank + " AS source_rank,"
                + payload + ",h.actor_user_id,h.changed_at,h.appointment_id,h.appointment_occurrence_version,"
                + "h.appointment_status_at_registration FROM " + table + " h "
                + (surface ? "JOIN tooth_surface_record s ON s.id=h.tooth_surface_record_id JOIN tooth_record t ON t.id=s.tooth_record_id"
                           : "JOIN tooth_record t ON t.id=h.tooth_record_id");
    }

    public record Filter(Long patientId, Integer toothFdi, Instant from, Instant to, Long appointmentId, Integer occurrenceVersion) { }
    private record Query(String sql, MapSqlParameterSource params) { }

    private Query query(Filter filter) {
        var params = new MapSqlParameterSource("patient", filter.patientId());
        var sql = new StringBuilder(" FROM (" + STREAM_SQL + ") e WHERE patient_id=:patient");
        if (filter.toothFdi() != null) { sql.append(" AND tooth_fdi=:fdi"); params.addValue("fdi", filter.toothFdi()); }
        if (filter.from() != null) {
            sql.append(" AND changed_at>=:from");
            Instant floor = filter.from().truncatedTo(ChronoUnit.MICROS);
            params.addValue("from", Timestamp.from(floor.equals(filter.from()) ? floor : floor.plus(1, ChronoUnit.MICROS)));
        }
        if (filter.to() != null) { sql.append(" AND changed_at<=:to"); params.addValue("to", Timestamp.from(filter.to().truncatedTo(ChronoUnit.MICROS))); }
        if (filter.appointmentId() != null) {
            sql.append(" AND appointment_id=:appointment AND appointment_occurrence_version=:version");
            params.addValue("appointment", filter.appointmentId()).addValue("version", filter.occurrenceVersion());
        }
        return new Query(sql.toString(), params);
    }

    public Page<OdontogramHistoryEvent> page(Filter filter, Pageable pageable) {
        var query = query(filter);
        Long total = jdbc.queryForObject("SELECT count(*)" + query.sql(), query.params(), Long.class);
        query.params().addValue("limit", pageable.getPageSize()).addValue("offset", pageable.getOffset());
        var events = jdbc.query("SELECT *" + query.sql()
                + " ORDER BY changed_at DESC,source_rank DESC,id DESC LIMIT :limit OFFSET :offset", query.params(), this::map);
        return new PageImpl<>(events, pageable, total == null ? 0 : total);
    }

    public List<OdontogramHistoryEvent> until(Long patientId, Instant at) {
        var query = query(new Filter(patientId, null, null, at, null, null));
        return jdbc.query("SELECT *" + query.sql() + " ORDER BY changed_at,source_rank,id", query.params(), this::map);
    }

    private OdontogramHistoryEvent map(ResultSet rs, int row) throws SQLException {
        Long appointment = rs.getObject("appointment_id", Long.class);
        var context = appointment == null ? null : new RecordedAppointmentContext(appointment,
                rs.getObject("appointment_occurrence_version", Integer.class),
                AppointmentStatus.valueOf(rs.getString("appointment_status_at_registration")));
        return new OdontogramHistoryEvent(rs.getLong("id"), rs.getLong("tooth_record_id"),
                rs.getObject("surface_record_id", Long.class), rs.getInt("tooth_fdi"),
                enumValue(ToothSurface.class, rs.getString("surface")),
                OdontogramHistoryEvent.Source.valueOf(rs.getString("source")),
                enumValue(ToothCondition.class, rs.getString("previous_condition")),
                enumValue(ToothCondition.class, rs.getString("new_condition")), rs.getString("previous_notes"),
                rs.getString("new_notes"), rs.getObject("treatment_id", Long.class),
                enumValue(TreatmentLinkChange.class, rs.getString("change_kind")), rs.getLong("actor_user_id"),
                rs.getTimestamp("changed_at").toInstant(), context);
    }
    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }
}
