package com.dentalclinic.appointment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import com.dentalclinic.appointment.infrastructure.config.AppointmentReminderProperties;
import com.dentalclinic.appointment.infrastructure.config.CommunicationProperties;
import com.dentalclinic.appointment.infrastructure.config.CommunicationSchedulingConfiguration;

class AppointmentReminderSchedulingTest {

    @Test
    void productorSeProgramaSinDispatcherConFrecuenciaDeSesentaSegundos() {
        CommunicationDispatcher dispatcher = mock(CommunicationDispatcher.class);
        AppointmentReminderJob producer = mock(AppointmentReminderJob.class);
        AppointmentReminderProperties reminders = new AppointmentReminderProperties(true, 0, 0, null, null, false);
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
        new CommunicationSchedulingConfiguration(dispatcher, communications(false), producer, reminders)
                .configureTasks(registrar);

        assertThat(registrar.getFixedDelayTaskList()).singleElement().satisfies(task -> {
            assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofSeconds(60));
            assertThat(task.getInitialDelayDuration()).isEqualTo(Duration.ofSeconds(30));
            task.getRunnable().run();
        });
        verify(producer).generateOnce();
        verifyNoInteractions(dispatcher);
    }

    @Test
    void arranqueSinActivacionNoProgramaProductorNiDispatcher() {
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
        new CommunicationSchedulingConfiguration(mock(CommunicationDispatcher.class), communications(false),
                mock(AppointmentReminderJob.class), new AppointmentReminderProperties(false, 0, 0, null, null, false))
                .configureTasks(registrar);
        assertThat(registrar.getFixedDelayTaskList()).isEmpty();
    }

    private CommunicationProperties communications(boolean enabled) {
        return new CommunicationProperties(new CommunicationProperties.Email(false, null),
                new CommunicationProperties.Dispatch(enabled, 0, 0, null, 0, null, null, null, null));
    }
}
