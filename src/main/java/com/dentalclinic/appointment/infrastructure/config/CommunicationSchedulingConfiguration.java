package com.dentalclinic.appointment.infrastructure.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import com.dentalclinic.appointment.application.CommunicationDispatcher;
import com.dentalclinic.appointment.application.AppointmentReminderJob;

/**
 * Programa independientemente el dispatcher (T1) y el productor de recordatorios (T4.1).
 *
 * <p>Ambos requieren activación operativa explícita. El productor se registra aunque el dispatcher
 * o SMTP estén deshabilitados; solo escribe outbox, notificaciones e intenciones. La frecuencia y
 * el retardo inicial provienen de sus respectivas propiedades.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class CommunicationSchedulingConfiguration implements SchedulingConfigurer {

    private final CommunicationDispatcher dispatcher;
    private final CommunicationProperties properties;
    private final AppointmentReminderJob reminderJob;
    private final AppointmentReminderProperties reminders;

    public CommunicationSchedulingConfiguration(CommunicationDispatcher dispatcher,
            CommunicationProperties properties, AppointmentReminderJob reminderJob,
            AppointmentReminderProperties reminders) {
        this.dispatcher = dispatcher;
        this.properties = properties;
        this.reminderJob = reminderJob;
        this.reminders = reminders;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        CommunicationProperties.Dispatch dispatch = properties.dispatch();
        if (dispatch.enabled()) {
            taskRegistrar.addFixedDelayTask(new FixedDelayTask(dispatcher::dispatchOnce,
                    dispatch.interval(), dispatch.initialDelay()));
        }
        if (reminders.enabled()) {
            taskRegistrar.addFixedDelayTask(new FixedDelayTask(reminderJob::generateOnce,
                    reminders.interval(), reminders.initialDelay()));
        }
    }
}
