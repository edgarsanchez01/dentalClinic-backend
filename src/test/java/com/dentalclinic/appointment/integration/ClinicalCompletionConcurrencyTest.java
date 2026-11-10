package com.dentalclinic.appointment.integration;

import java.util.concurrent.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import com.dentalclinic.appointment.application.AppointmentService;
import com.dentalclinic.checkout.CheckoutFlowSupport;
import static org.assertj.core.api.Assertions.*;

/** Real lock wait: a completion must observe the lifecycle/occurrence committed ahead of it. */
class ClinicalCompletionConcurrencyTest extends CheckoutFlowSupport {
    @Autowired private AppointmentService appointments;

    @ParameterizedTest @ValueSource(strings={"cancel","no-show","reschedule"})
    void lifecycleWinnerIsObservedAfterCompletionWaits(String action) throws Exception {
        Long clinic=jdbc.queryForObject("INSERT INTO clinic(name,timezone) VALUES ('Test','UTC') RETURNING id",Long.class);
        var next=java.time.LocalDate.now(clock.withZone(java.time.ZoneOffset.UTC)).plusDays(1)
                .atTime(10,0).toInstant(java.time.ZoneOffset.UTC);
        int day=java.time.LocalDate.ofInstant(next,java.time.ZoneOffset.UTC).getDayOfWeek().getValue();
        jdbc.update("INSERT INTO clinic_schedule(clinic_id,day_of_week,start_time,end_time) VALUES (?,?,'09:00','18:00')",clinic,day);
        jdbc.update("INSERT INTO dentist_schedule(dentist_id,day_of_week,start_time,end_time) VALUES (?,?,'09:00','18:00')",dentistId,day);
        try(var executor=Executors.newSingleThreadExecutor()) {
            Future<RuntimeException> future=new TransactionTemplate(transactions).execute(status -> {
                jdbc.queryForObject("SELECT id FROM appointment WHERE id=? FOR UPDATE",Long.class,scheduled);
                var waiting=executor.submit(() -> {
                    try { appointments.complete(scheduled,doctor,1,"waiting");return null; }
                    catch(RuntimeException error) { return error; }
                });
                awaitWaiter();
                if(action.equals("cancel")) appointments.cancel(scheduled,owner);
                else if(action.equals("no-show")) appointments.markNoShow(scheduled,owner);
                else appointments.reschedule(scheduled,doctor,new AppointmentService.RescheduleData(next,next.plusSeconds(3600),1));
                return waiting;
            });
            assertThat(future.get(20,TimeUnit.SECONDS)).isInstanceOf(com.dentalclinic.scheduling.domain.ScheduleConflictException.class);
        }
        assertThat(count("financial_movement")).isZero();
        assertThat(count("patient_feedback")).isZero();
        assertThat(count("http_idempotency")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM appointment_event WHERE event_type='APPOINTMENT_COMPLETED'",Long.class)).isZero();
    }

    private void awaitWaiter() {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<deadline) {
            jdbc.execute("SELECT pg_stat_clear_snapshot()");
            if(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() "
                    + "AND pid<>pg_backend_pid() AND wait_event_type='Lock'",Long.class)>0) return;
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        throw new AssertionError("No se observo la espera de lock de cita");
    }
}
