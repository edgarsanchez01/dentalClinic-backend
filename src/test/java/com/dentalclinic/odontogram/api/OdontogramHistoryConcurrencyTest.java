package com.dentalclinic.odontogram.api;

import static org.assertj.core.api.Assertions.*;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.*;
import javax.sql.DataSource;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import com.dentalclinic.appointment.application.AppointmentClinicalContextService.Selection;
import com.dentalclinic.identity.TestTime;
import com.dentalclinic.odontogram.application.OdontogramService.*;
import com.dentalclinic.odontogram.application.OdontogramHistoryService;
import com.dentalclinic.odontogram.domain.*;
import com.dentalclinic.scheduling.domain.ScheduleConflictException;

/** Real PostgreSQL lock waits, bounded latches/futures; no sleep-based guess of race order. */
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:16:///dentalclinic_odontogram_e2_concurrency?TC_TMPFS=/var/lib/postgresql/data:rw"
})
class OdontogramHistoryConcurrencyTest extends OdontogramHistoryTestSupport {
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private EntityManager entityManager;
    @Autowired private OdontogramHistoryService history;

    @ParameterizedTest @ValueSource(ints = {0, 1, 2})
    void dosEscritoresGeneralesContextualesOMixtosSeSerializanSinPerderEventos(int mode) throws Exception {
        Long initial = treatment(), a = treatment(), b = treatment();
        Long tooth = createTooth("Inicial", List.of(initial), null);
        Selection contextA = mode == 0 ? null : new Selection(appointment(9), 1);
        Selection contextB = mode == 1 ? new Selection(appointment(11), 1) : null;
        var pool = Executors.newFixedThreadPool(2);
        Future<?> first, second;
        try (var blocker = dataSource.getConnection()) {
            lock(blocker, "tooth_record", tooth);
            first = pool.submit(() -> odontogram.update(patient.getId(), 21,
                    new ToothData(21, ToothCondition.CARIES, "A", List.of(a)), actor(), contextA));
            second = pool.submit(() -> odontogram.update(patient.getId(), 21,
                    new ToothData(21, ToothCondition.CROWN, "B", List.of(b)), actor(), contextB));
            awaitLockWaiters(2);
            blocker.commit();
            first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        var conditions = jdbcTemplate.queryForList("SELECT previous_condition,new_condition FROM tooth_history ORDER BY id");
        var notes = jdbcTemplate.queryForList("SELECT previous_notes,new_notes FROM tooth_notes_history ORDER BY id");
        assertThat(conditions).hasSize(3);
        assertThat(conditions.get(2).get("previous_condition")).isEqualTo(conditions.get(1).get("new_condition"));
        assertThat(notes).hasSize(3);
        assertThat(notes.get(1).get("previous_notes")).isEqualTo("Inicial");
        assertThat(notes.get(2).get("previous_notes")).isEqualTo(notes.get(1).get("new_notes"));
        assertThat(jdbcTemplate.queryForObject("SELECT notes FROM tooth_record WHERE id=?", String.class, tooth))
                .isEqualTo(notes.getLast().get("new_notes"));
        assertThat(countRows("tooth_treatment_history")).isEqualTo(5);
        assertThat(countRows("treatment_tooth")).isEqualTo(1);
        assertThat(countRows("appointment_occurrence_snapshot")).isEqualTo(mode == 0 ? 0 : mode == 1 ? 2 : 1);
    }

    @Test void entidadLeidaAntesDelLockSeRefrescaConElUltimoEstadoConfirmado() throws Exception {
        Long tooth = createTooth("Inicial", List.of(), null);
        var loaded = new CountDownLatch(1);
        var committed = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var staleWriter = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                assertThat(entityManager.find(ToothRecord.class, tooth).getNotes()).isEqualTo("Inicial");
                loaded.countDown(); await(committed);
                return odontogram.update(patient.getId(), 21,
                        new ToothData(21, ToothCondition.CROWN, "Final", List.of()), actor());
            }));
            assertThat(loaded.await(5, TimeUnit.SECONDS)).isTrue();
            odontogram.update(patient.getId(), 21, new ToothData(21, ToothCondition.CARIES, "Confirmada", List.of()), actor());
            committed.countDown();
            staleWriter.get(10, TimeUnit.SECONDS);
        } finally { committed.countDown(); pool.shutdownNow(); }
        assertThat(jdbcTemplate.queryForObject("SELECT previous_notes FROM tooth_notes_history ORDER BY id DESC LIMIT 1", String.class))
                .isEqualTo("Confirmada");
    }

    @Test void reprogramacionGanadoraHace409SinSnapshotNiClinicaParcial() throws Exception {
        Long tooth = createTooth("Inicial", List.of(), null), appointment = appointment(9);
        var pool = Executors.newSingleThreadExecutor();
        try (var blocker = dataSource.getConnection()) {
            lock(blocker, "appointment", appointment);
            var writer = pool.submit(() -> odontogram.update(patient.getId(), 21,
                    new ToothData(21, ToothCondition.CARIES, "Obsoleta", List.of()), actor(), new Selection(appointment, 1)));
            awaitLockWaiters(1);
            try (var sql = blocker.prepareStatement("UPDATE appointment SET occurrence_version=2 WHERE id=?")) {
                sql.setLong(1, appointment); sql.executeUpdate();
            }
            blocker.commit();
            assertThatThrownBy(() -> writer.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(ScheduleConflictException.class);
        } finally { pool.shutdownNow(); }
        assertThat(jdbcTemplate.queryForObject("SELECT notes FROM tooth_record WHERE id=?", String.class, tooth)).isEqualTo("Inicial");
        assertThat(countRows("tooth_history")).isEqualTo(1);
        assertThat(countRows("appointment_occurrence_snapshot")).isZero();
    }

    @Test void altaConcurrenteDePiezaSoloConservaUnaRaizYRevierteSnapshotPerdedor() throws Exception {
        Long appointment = appointment(9);
        var inserted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                var result = odontogram.create(patient.getId(), new ToothData(22, ToothCondition.HEALTHY, "Ganadora", List.of()), actor());
                inserted.countDown(); await(release); return result;
            }));
            assertThat(inserted.await(5, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> odontogram.create(patient.getId(),
                    new ToothData(22, ToothCondition.CARIES, "Perdedora", List.of()), actor(), new Selection(appointment, 1)));
            awaitLockWaiters(1); release.countDown();
            first.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(DataIntegrityViolationException.class);
        } finally { release.countDown(); pool.shutdownNow(); }
        assertThat(countRows("tooth_record")).isEqualTo(1);
        assertThat(countRows("tooth_history")).isEqualTo(1);
        assertThat(countRows("tooth_notes_history")).isEqualTo(1);
        assertThat(countRows("appointment_occurrence_snapshot")).isZero();
    }

    @Test void altaConcurrenteDeSuperficieSeSerializaPorLaPiezaPadre() throws Exception {
        createTooth(null, List.of(), null);
        var inserted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        var data = new SurfaceData(ToothSurface.MESIAL, ToothCondition.CARIES, "Superficie");
        try {
            var first = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                var result = odontogram.createSurface(patient.getId(), 21, data, actor());
                inserted.countDown(); await(release); return result;
            }));
            assertThat(inserted.await(5, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> odontogram.createSurface(patient.getId(), 21, data, actor()));
            awaitLockWaiters(1); release.countDown();
            first.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(ToothSurfaceAlreadyExistsException.class);
        } finally { release.countDown(); pool.shutdownNow(); }
        assertThat(countRows("tooth_surface_record")).isEqualTo(1);
        assertThat(countRows("tooth_surface_history")).isEqualTo(1);
        assertThat(countRows("tooth_surface_notes_history")).isEqualTo(1);
    }

    @Test void lecturaHistoricaYCorrienteUsanElMismoSnapshotAunqueOtroEscritorConfirme() throws Exception {
        Long appointment = appointment(9);
        createTooth("Inicial", List.of(), new Selection(appointment, 1));
        var established = new CountDownLatch(1);
        var committed = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var transaction = new TransactionTemplate(transactions);
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            var reader = pool.submit(() -> transaction.execute(status -> {
                assertThat(jdbcTemplate.queryForObject("SELECT notes FROM tooth_record", String.class)).isEqualTo("Inicial");
                established.countDown(); await(committed);
                return history.at(patient.getId(), null, appointment, 1, 0, 20, actor());
            }));
            assertThat(established.await(5, TimeUnit.SECONDS)).isTrue();
            odontogram.update(patient.getId(), 21, new ToothData(21, ToothCondition.CROWN, "Posterior", List.of()), actor());
            committed.countDown();
            var view = reader.get(10, TimeUnit.SECONDS);
            assertThat(view.snapshot().teeth().getFirst().notes().value()).isEqualTo("Inicial");
            assertThat(view.currentState().getFirst().record().getNotes()).isEqualTo("Inicial");
            assertThat(view.attributedEvents().getTotalElements()).isEqualTo(2);
            assertThat(jdbcTemplate.queryForObject("SELECT notes FROM tooth_record", String.class)).isEqualTo("Posterior");
        } finally { committed.countDown(); pool.shutdownNow(); }
    }

    private void lock(Connection connection, String table, Long id) throws Exception {
        connection.setAutoCommit(false);
        try (var statement = connection.prepareStatement("SELECT id FROM " + table + " WHERE id=? FOR UPDATE")) {
            statement.setLong(1, id); statement.executeQuery().close();
        }
    }
    private void awaitLockWaiters(int count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject("SELECT count(*) FROM pg_stat_activity"
                    + " WHERE datname=current_database() AND wait_event_type='Lock'", Integer.class);
            if (waiting != null && waiting >= count) return;
            Thread.sleep(10);
        }
        fail("No se observaron %s esperas reales de lock en PostgreSQL", count);
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Barrera de concurrencia agotada"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}
