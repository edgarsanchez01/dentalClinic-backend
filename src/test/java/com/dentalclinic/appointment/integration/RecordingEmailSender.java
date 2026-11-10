package com.dentalclinic.appointment.integration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.dentalclinic.appointment.application.EmailSendException;
import com.dentalclinic.appointment.application.EmailSender;
import com.dentalclinic.appointment.application.OutboundEmail;

/**
 * Emisor falso: no abre red, nunca envía un correo real. Registra lo enviado y permite simular
 * fallos y latencia para probar retry y concurrencia.
 */
public class RecordingEmailSender implements EmailSender {

    private final AtomicInteger sent = new AtomicInteger();
    private final List<OutboundEmail> messages = new CopyOnWriteArrayList<>();
    private volatile boolean fail;
    private volatile long sendDelayMillis;

    public void reset() {
        sent.set(0);
        messages.clear();
        fail = false;
        sendDelayMillis = 0;
    }

    public void alwaysFail() {
        this.fail = true;
    }

    public void succeed() {
        this.fail = false;
    }

    public void setSendDelayMillis(long sendDelayMillis) {
        this.sendDelayMillis = sendDelayMillis;
    }

    public int sentCount() {
        return sent.get();
    }

    public List<OutboundEmail> messages() {
        return Collections.unmodifiableList(new ArrayList<>(messages));
    }

    @Override
    public void send(OutboundEmail email) {
        sleep();
        if (fail) {
            throw new EmailSendException("fallo SMTP simulado");
        }
        sent.incrementAndGet();
        messages.add(email);
    }

    private void sleep() {
        long delay = sendDelayMillis;
        if (delay <= 0) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new EmailSendException("interrumpido durante el envio simulado", ex);
        }
    }
}
