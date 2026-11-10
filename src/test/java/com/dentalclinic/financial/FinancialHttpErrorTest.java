package com.dentalclinic.financial;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpServletRequest;

import com.dentalclinic.financial.api.FinancialExceptionHandler;

/** Incluye fallos nativos de locks que no siempre pasan por la traduccion de Spring Data. */
class FinancialHttpErrorTest {
    @Test
    void erroresDePersistenciaYLocksSoloDevuelvenConflictoGenerico() {
        var handler = new FinancialExceptionHandler();
        var request = new MockHttpServletRequest("POST", "/api/charges/1/payments");
        for (RuntimeException failure : List.of(
                new CannotAcquireLockException("SELECT pg_advisory_xact_lock; stack interna"),
                new DataIntegrityViolationException("financial_movement constraint interna"),
                new jakarta.persistence.LockTimeoutException("SELECT charge FOR UPDATE"),
                new jakarta.persistence.PessimisticLockException("tabla e internals SQL"))) {
            var response = handler.persistence(failure, request);
            assertThat(response.getStatusCode().value()).isEqualTo(409);
            assertThat(response.getBody().path()).isEqualTo("/api/charges/1/payments");
            assertThat(response.getBody().message()).doesNotContain("SELECT", "SQL", "constraint", "stack", "pg_advisory");
        }
    }
}
