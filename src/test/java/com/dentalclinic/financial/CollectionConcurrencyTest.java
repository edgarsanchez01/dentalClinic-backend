package com.dentalclinic.financial;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import com.dentalclinic.appointment.application.AppointmentService;
import com.dentalclinic.financial.application.CollectionCommand;
import com.dentalclinic.financial.domain.FinancialConflictException;
import com.dentalclinic.financial.application.command.*;
import com.dentalclinic.financial.domain.PaymentMethod;
import static org.assertj.core.api.Assertions.*;

/** Real transactions, a start barrier and observable PostgreSQL lock waits. */
class CollectionConcurrencyTest extends CollectionFlowSupport {

    @ParameterizedTest @ValueSource(strings={"same","different-key","different-body","different-actor"})
    void concurrentCheckoutsHaveOneIdentityAndNoDuplicateEffects(String mode) throws Exception {
        var first=collectionCommand("one",null,"200");
        var second=(mode.equals("same") || mode.equals("different-actor")) ? first : collectionParser.parse(scheduled,
                mode.equals("different-key") ? "two" : "one",body(token(),null,"201"));
        var results=race(() -> collections.collect(first,reception),
                () -> collections.collect(second,mode.equals("different-actor")?owner:reception),"appointment",scheduled);
        assertThat(results.stream().filter(r -> r.failure==null).count()).isEqualTo(mode.equals("same") ? 2 : 1);
        if(mode.equals("same")) assertThat(results.get(0).value).isEqualTo(results.get(1).value);
        assertThat(count("appointment_checkout")).isZero();
        assertThat(count("charge")).isEqualTo(2);
        assertThat(count("financial_movement")).isEqualTo(1);
        assertThat(count("patient_feedback")).isEqualTo(1);
        assertThat(receipts()).isEqualTo(1);
    }

    @Test void administrativeCreationAndEmptyChargeCheckoutSerializeOnAppointment() throws Exception {
        var command=collectionCommand("collection",null,null);
        var results=race(() -> collections.collect(command,reception),
                () -> operations.confirmCharge(new ConfirmChargeCommand(patientId,scheduled,
                        List.of(new ChargeItemData(null,dentistId,"Cargo recepcion",1,new BigDecimal("500"))),
                        null,"reception-charge"),new com.dentalclinic.identity.infrastructure.security.AuthenticatedUser(
                                receptionId,com.dentalclinic.identity.domain.Role.RECEPTIONIST)),"appointment",scheduled);
        assertThat(results.get(1).failure).isNull();
        if(results.get(0).failure==null) {
            assertThat(count("charge")).isEqualTo(3); // Additional administrative charge is legitimate.
            assertThat(count("appointment_checkout")).isZero();
        } else {
            assertThat(results.get(0).failure).isInstanceOf(FinancialConflictException.class);
            assertClinicallyCompleted();
            assertThat(count("charge")).isEqualTo(2);
        }
    }

    @ParameterizedTest @ValueSource(strings={"payment","reversal","cancel"})
    void administrativeMutationBeforeCheckoutChargeLockInvalidatesToken(String operation) throws Exception {
        Long charge=administrativeCharge("500");
        Long prior=operation.equals("reversal") ? payment(charge,patientId,"100") : null;
        CollectionCommand command=collectionCommand("collection",charge,null);
        try(var executor=Executors.newSingleThreadExecutor()) {
            Future<Outcome> future=new TransactionTemplate(transactions).execute(status -> {
                jdbc.queryForObject("SELECT id FROM charge WHERE id=? FOR UPDATE",Long.class,charge);
                var started=executor.submit(() -> outcome(() -> collections.collect(command,reception)));
                awaitWaiters(1);
                if(operation.equals("payment")) payment(charge,patientId,"100");
                else if(operation.equals("reversal")) reversal(prior,charge,patientId,"100");
                else jdbc.update("UPDATE charge SET cancelled_at=now(),cancelled_by_user_id=?,cancellation_reason='Test',version=version+1 WHERE id=?",ownerId,charge);
                return started;
            });
            var result=future.get(15,TimeUnit.SECONDS);
            assertThat(result.failure).isInstanceOf(FinancialConflictException.class);
            assertClinicallyCompleted();
        }
    }

    @Test void administrativePaymentAfterCheckoutWaitsAndPreservesItsContract() throws Exception {
        Long charge=administrativeCharge("500");
        var cmd=collectionCommand("collection",charge,"300");
        var results=race(() -> collections.collect(cmd,reception), () -> operations.registerPayment(
                new RegisterPaymentCommand(charge,PaymentMethod.CASH,new BigDecimal("300"),null,"admin"),
                new com.dentalclinic.identity.infrastructure.security.AuthenticatedUser(
                        receptionId,com.dentalclinic.identity.domain.Role.RECEPTIONIST)),"charge",charge);
        assertThat(results.stream().filter(r -> r.failure==null).count()).isEqualTo(1);
        assertThat(count("financial_movement")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT sum(amount) FROM financial_movement",BigDecimal.class)).isEqualByComparingTo("300");
    }

    @ParameterizedTest @ValueSource(strings={"reversal","cancel"})
    void publicOwnerCorrectionRacesWithoutChangingDurableCheckoutIdentity(String action) throws Exception {
        Long charge=administrativeCharge("500");
        Long original=action.equals("reversal") ? payment(charge,patientId,"100") : null;
        var cmd=collectionCommand("collection",charge,null);
        Callable<?> correction=action.equals("reversal")
                ? () -> operations.reversePayment(new ReversePaymentCommand(charge,original,"Error","reverse"),owner)
                : () -> operations.cancelCharge(new CancelChargeCommand(charge,"Error","cancel"),owner);
        var results=race(() -> collections.collect(cmd,reception),correction,"charge",charge);
        assertThat(results.get(1).failure).isNull();
        if(results.get(0).failure==null) {
            var response=(com.dentalclinic.financial.api.dto.AppointmentCollectionResponse)results.get(0).value;
            assertThat(collections.collect(cmd,reception)).isEqualTo(response);
            assertThat(response.financialAtCollection().chargeId()).isEqualTo(charge);
            assertThat(count("patient_feedback")).isEqualTo(1);
        } else {
            assertThat(results.get(0).failure).isInstanceOf(FinancialConflictException.class);
            assertClinicallyCompleted();
        }
    }

    @Test void clinicalCompleteAndCheckoutCannotDuplicateClinicalEffects() throws Exception {
        var cmd=collectionCommand("collection",null,"200");
        var results=race(() -> collections.collect(cmd,reception), () -> appointments.complete(scheduled,doctor,1,"another-complete"),"appointment",scheduled);
        assertThat(results.stream().filter(r -> r.failure==null).count()).isEqualTo(1);
        assertThat(count("patient_feedback")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM appointment_event WHERE event_type='APPOINTMENT_COMPLETED'",Long.class)).isEqualTo(1);
        assertThat(count("appointment_checkout")).isZero();
        assertThat(count("charge")).isEqualTo(2);
        assertThat(count("financial_movement")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"cancel","no-show","reschedule"})
    void clinicalStateWinnerIsSeenAfterCheckoutWaits(String action) throws Exception {
        Long clinic=jdbc.queryForObject("INSERT INTO clinic(name,timezone) VALUES ('Test','UTC') RETURNING id",Long.class);
        var next=java.time.LocalDate.now(clock.withZone(java.time.ZoneOffset.UTC)).plusDays(1)
                .atTime(10,0).toInstant(java.time.ZoneOffset.UTC);
        int day=java.time.LocalDate.ofInstant(next,java.time.ZoneOffset.UTC).getDayOfWeek().getValue();
        jdbc.update("INSERT INTO clinic_schedule(clinic_id,day_of_week,start_time,end_time) VALUES (?,?,'09:00','18:00')",clinic,day);
        jdbc.update("INSERT INTO dentist_schedule(dentist_id,day_of_week,start_time,end_time) VALUES (?,?,'09:00','18:00')",dentistId,day);
        // A clinical administrative mutation cannot reopen an already completed appointment.
        var cmd=collectionCommand("waiting",null,"200");
        try(var executor=Executors.newSingleThreadExecutor()) {
            Future<Outcome> future=new TransactionTemplate(transactions).execute(status -> {
                jdbc.queryForObject("SELECT id FROM appointment WHERE id=? FOR UPDATE",Long.class,scheduled);
                var waiting=executor.submit(() -> outcome(() -> collections.collect(cmd,reception)));
                awaitWaiters(1);
                return waiting;
            });
            var result=future.get(15,TimeUnit.SECONDS);
            assertThat(result.failure).isNull();
            assertThatThrownBy(() -> {
                if(action.equals("cancel")) appointments.cancel(scheduled,owner);
                else if(action.equals("no-show")) appointments.markNoShow(scheduled,owner);
                else appointments.reschedule(scheduled,doctor,new AppointmentService.RescheduleData(next,next.plusSeconds(3600),1));
            }).isInstanceOf(com.dentalclinic.scheduling.domain.ScheduleConflictException.class);
            assertThat(count("appointment_checkout")).isZero();
            assertThat(count("charge")).isEqualTo(2);
            assertThat(count("financial_movement")).isEqualTo(1);
            assertThat(receipts()).isEqualTo(1);
            assertClinicallyCompleted();
        }
    }

    private List<Outcome> race(Callable<?> a,Callable<?> b,String table,Long id) throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            CountDownLatch ready=new CountDownLatch(2), start=new CountDownLatch(1);
            List<Future<Outcome>> futures=new TransactionTemplate(transactions).execute(status -> {
                jdbc.queryForObject("SELECT id FROM "+table+" WHERE id=? FOR UPDATE",Long.class,id);
                var pair=List.of(pool.submit(() -> run(a,ready,start)),pool.submit(() -> run(b,ready,start)));
                try { assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue(); }
                catch(InterruptedException e) { throw new AssertionError(e); }
                start.countDown(); awaitWaiters(2); return pair;
            });
            return List.of(futures.get(0).get(20,TimeUnit.SECONDS),futures.get(1).get(20,TimeUnit.SECONDS));
        }
    }
    private Outcome run(Callable<?> call,CountDownLatch ready,CountDownLatch start) throws Exception {
        ready.countDown(); assertThat(start.await(10,TimeUnit.SECONDS)).isTrue(); return outcome(call);
    }
    private Outcome outcome(Callable<?> call) {
        try { return new Outcome(call.call(),null); } catch(Exception e) { return new Outcome(null,e); }
    }
    private void awaitWaiters(int expected) {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<deadline) {
            jdbc.execute("SELECT pg_stat_clear_snapshot()");
            long n=jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() "
                    + "AND pid<>pg_backend_pid() AND wait_event_type='Lock'",Long.class);
            if(n>=expected) return;
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        throw new AssertionError("PostgreSQL no mostro la espera de locks esperada");
    }
    private record Outcome(Object value,Exception failure) { }
}
