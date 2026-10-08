package com.dentalclinic.appointment.application;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import com.dentalclinic.appointment.api.dto.AppointmentResponse;
import com.dentalclinic.appointment.api.dto.CreateAppointmentRequest;
import com.dentalclinic.appointment.application.AppointmentService.AppointmentData;
import com.dentalclinic.appointment.domain.HttpIdempotency;
import com.dentalclinic.appointment.infrastructure.repository.HttpIdempotencyRepository;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;
import com.dentalclinic.scheduling.domain.ScheduleConflictException;
import com.dentalclinic.scheduling.domain.exception.InvalidScheduleException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Idempotencia de creación de citas (A-16/O-2) en la capa HTTP.
 *
 * <p>Proceso: la clave es obligatoria; si ya existe para el actor+operación, hace replay (mismo
 * hash ⇒ misma respuesta) o 409 <strong>sin consumir la clave</strong> (hash distinto). Si no
 * existe, delega en {@link AppointmentService#create} para que la consume de forma atómica con la
 * operación de negocio (misma transacción: si la cita pasa rollback, la clave queda libre).
 *
 * <p>Si la creación falla con violación de integridad, se re-lee la clave: si otra petición ganó y
 * dejó fila, replay/409; si no hay fila, era la constraint EXCLUDE de slot — el mensaje accionable
 * ("ese horario ya fue tomado") en lugar de la violación genérica.
 *
 * <p>PostgreSQL también puede detectar un deadlock al comprobar la EXCLUDE entre inserciones
 * simultáneas. Tras el rollback completo del servicio, se reintenta una vez en una nueva
 * transacción, consultando primero la respuesta ganadora. No se reintenta dentro de una
 * transacción abortada ni se consumen claves fuera de la transacción de creación.
 */
@Component
public class AppointmentCreateCoordinator {

    private static final String OPERATION = "CREATE_APPOINTMENT";

    private final AppointmentService appointmentService;
    private final HttpIdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;

    public AppointmentCreateCoordinator(AppointmentService appointmentService,
                                        HttpIdempotencyRepository idempotencyRepository,
                                        ObjectMapper objectMapper) {
        this.appointmentService = appointmentService;
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
    }

    public AppointmentResponse create(AuthenticatedUser actor, CreateAppointmentRequest request,
                                      String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new InvalidScheduleException(
                    "La cabecera Idempotency-Key es obligatoria para crear citas.");
        }
        String hash = requestHash(request);
        try {
            return createOrReplay(actor, request, idempotencyKey, hash);
        } catch (CannotAcquireLockException ex) {
            // El proxy @Transactional del servicio ya terminó el rollback antes de llegar aquí.
            return createOrReplay(actor, request, idempotencyKey, hash);
        }
    }

    private AppointmentResponse createOrReplay(AuthenticatedUser actor, CreateAppointmentRequest request,
                                               String idempotencyKey, String hash) {
        HttpIdempotency existing = idempotencyRepository
                .findByIdentity(actor.userId(), OPERATION, idempotencyKey)
                .orElse(null);
        if (existing != null) {
            return replayOrConflict(existing, hash);
        }
        try {
            return appointmentService.create(toData(request), actor, idempotencyKey, hash)
                    .response();
        } catch (DataIntegrityViolationException | ScheduleConflictException ex) {
            HttpIdempotency winner = idempotencyRepository
                    .findByIdentity(actor.userId(), OPERATION, idempotencyKey)
                    .orElse(null);
            if (winner != null) {
                return replayOrConflict(winner, hash);
            }
            if (ex instanceof ScheduleConflictException conflict) {
                // El ganador puede haber confirmado entre la lectura de clave y assertBookable.
                // Sin respuesta idempotente, se conserva la explicación original del negocio.
                throw conflict;
            }
            // No es el choque de clave de petición: es la EXCLUDE de slot u otra constraint real.
            throw new ScheduleConflictException("Ese horario ya fue tomado. Selecciona otro horario.");
        }
    }

    private AppointmentResponse replayOrConflict(HttpIdempotency existing, String hash) {
        if (!existing.getRequestHash().equals(hash)) {
            throw new ScheduleConflictException(
                    "La cabecera Idempotency-Key ya fue usada por otra petición con distinto contenido.");
        }
        return objectMapper.convertValue(existing.getResponseBody(), AppointmentResponse.class);
    }

    private String requestHash(CreateAppointmentRequest request) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(request);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("No se pudo calcular el hash de la petición", e);
        }
    }

    private static AppointmentData toData(CreateAppointmentRequest request) {
        return new AppointmentData(request.patientId(), request.dentistId(), request.startAt(),
                request.endAt(), request.reason(), request.notes());
    }
}
