package com.dentalclinic.medicalintake;

import static org.assertj.core.api.Assertions.*;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpServletRequest;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.dentalclinic.identity.api.RestExceptionHandler;

class SensitiveExceptionLoggingTest {
    @Test void databaseAndUnexpectedErrorsNeverLogMessageCauseOrMedicalValues() {
        String marker = "synthetic-sensitive-allergy-medication";
        Logger logger = (Logger) LoggerFactory.getLogger(RestExceptionHandler.class);
        Level previous = logger.getLevel();
        var events = new ListAppender<ILoggingEvent>();
        events.start();
        logger.addAppender(events);
        logger.setLevel(Level.INFO);
        try {
            var handler = new RestExceptionHandler();
            var request = new MockHttpServletRequest("PUT", "/api/patients/me/medical-intake/draft/medical");
            var cause = new SQLException("Failing row contains " + marker);
            var conflict = handler.handleConflict(new DataIntegrityViolationException(marker, cause), request);
            var unexpected = handler.handleUnexpected(new RuntimeException(marker, cause), request);
            assertThat(conflict.getBody().toString()).doesNotContain(marker);
            assertThat(unexpected.getBody().toString()).doesNotContain(marker);
            assertThat(events.list).hasSize(2).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain(marker, "Failing row");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally { logger.detachAppender(events); logger.setLevel(previous); events.stop(); }
    }
}
