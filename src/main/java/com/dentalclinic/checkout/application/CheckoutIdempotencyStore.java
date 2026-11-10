package com.dentalclinic.checkout.application;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.appointment.domain.HttpIdempotency;
import com.dentalclinic.appointment.infrastructure.repository.HttpIdempotencyRepository;
import com.dentalclinic.checkout.api.dto.AppointmentCheckoutResponse;
import com.dentalclinic.checkout.domain.CheckoutErrorCode;
import com.dentalclinic.checkout.domain.CheckoutException;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

/**
 * Idempotencia durable del cierre integrado sobre {@code http_idempotency}.
 *
 * <p>Operacion exclusiva {@code APPOINTMENT_CHECKOUT}, por actor y clave. Toma el mismo lock
 * asesor que las operaciones financieras pero en un namespace propio; la clave se inserta en la
 * misma transaccion del cargo, pago, completado y {@code appointment_checkout}. No reserva claves
 * antes de tiempo ni crea tablas nuevas. El snapshot de respuesta se versiona por separado del
 * formato de hash de la solicitud.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class CheckoutIdempotencyStore {

    static final String NAMESPACE = "dentalclinic-checkout";
    static final String OPERATION = "APPOINTMENT_CHECKOUT";
    static final int SNAPSHOT_VERSION = 1;
    static final int REQUEST_CANONICALIZATION_VERSION = 1;

    private final HttpIdempotencyRepository idempotency;
    private final EntityManager entityManager;
    private final ObjectMapper requestMapper;
    private final ObjectMapper snapshotMapper;

    CheckoutIdempotencyStore(HttpIdempotencyRepository idempotency, EntityManager entityManager,
            ObjectMapper mapper) {
        this.idempotency = idempotency;
        this.entityManager = entityManager;
        this.requestMapper = mapper.copy()
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        SimpleModule decimals = new SimpleModule();
        decimals.addSerializer(BigDecimal.class, ToStringSerializer.instance);
        this.snapshotMapper = mapper.copy().registerModule(decimals)
                .setVisibility(PropertyAccessor.ALL, Visibility.NONE)
                .setVisibility(PropertyAccessor.FIELD, Visibility.ANY);
    }

    /**
     * Bloquea la identidad (actor+operacion+clave) y devuelve el hash canonico de la solicitud.
     * Debe invocarse antes de bloquear la cita.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    String lockRequest(AuthenticatedUser actor, CheckoutCommand command) {
        CheckoutTransactionGuard.requireWritable();
        if (command == null || command.idempotencyKey() == null
                || command.idempotencyKey().isEmpty()) {
            throw new CheckoutException(CheckoutErrorCode.CHECKOUT_INVALID_REQUEST,
                    "Idempotency-Key es obligatoria.");
        }
        byte[] identity = jsonBytes(List.of(NAMESPACE, actor.userId(), OPERATION,
                command.idempotencyKey()));
        long lockId = ByteBuffer.wrap(digest(identity)).getLong();
        entityManager.createNativeQuery("SELECT 1 FROM pg_advisory_xact_lock(:key)")
                .setParameter("key", lockId).getSingleResult();
        return HexFormat.of().formatHex(digest(jsonBytes(fingerprint(command))));
    }

    /**
     * Devuelve el snapshot original o {@code null} si la clave aun no fue usada. Un mismo hash
     * reejecuta el replay; un hash distinto es conflicto. No se reejecuta ninguna escritura.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    AppointmentCheckoutResponse replay(AuthenticatedUser actor, String key, String hash) {
        HttpIdempotency existing = idempotency
                .findByIdentity(actor.userId(), OPERATION, key).orElse(null);
        if (existing == null) {
            return null;
        }
        Map<String, Object> body = existing.getResponseBody();
        if (body == null
                || !Integer.valueOf(SNAPSHOT_VERSION).equals(body.get("snapshotVersion"))
                || !Integer.valueOf(REQUEST_CANONICALIZATION_VERSION)
                        .equals(body.get("requestCanonicalizationVersion"))) {
            throw new IllegalStateException("Snapshot idempotente del cierre ausente o incompatible.");
        }
        if (!existing.getRequestHash().equals(hash)) {
            throw new CheckoutException(CheckoutErrorCode.CHECKOUT_IDEMPOTENCY_CONFLICT,
                    "Idempotency-Key ya fue usada con una solicitud diferente.");
        }
        return snapshotMapper.convertValue(body.get("result"),
                AppointmentCheckoutResponse.class);
    }

    /** Persiste el snapshot en la misma transaccion que el cierre; no refresca entidades ajenas. */
    @Transactional(propagation = Propagation.MANDATORY)
    void remember(AuthenticatedUser actor, String key, String hash,
            AppointmentCheckoutResponse response) {
        CheckoutTransactionGuard.requireWritable();
        entityManager.flush();
        Map<String, Object> snapshot = snapshotMapper.convertValue(response,
                new TypeReference<>() { });
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("snapshotVersion", SNAPSHOT_VERSION);
        body.put("requestCanonicalizationVersion", REQUEST_CANONICALIZATION_VERSION);
        body.put("result", snapshot);
        idempotency.save(HttpIdempotency.create(actor.userId(), OPERATION, key, hash, 200, body));
        entityManager.flush();
    }

    private static Map<String, Object> fingerprint(CheckoutCommand command) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("canonicalizationVersion", REQUEST_CANONICALIZATION_VERSION);
        root.put("appointmentId", command.appointmentId());
        root.put("expectedOccurrenceVersion", command.expectedOccurrenceVersion());
        root.put("financialContextToken", command.financialContextToken());
        Map<String, Object> charge = new LinkedHashMap<>();
        charge.put("action", command.chargeAction() == null ? null : command.chargeAction().name());
        charge.put("chargeId", command.chargeId());
        charge.put("expectedTotal", decimal(command.expectedTotal()));
        charge.put("confirmed", true);
        root.put("charge", charge);
        Map<String, Object> payment = new LinkedHashMap<>();
        payment.put("action", command.paymentAction() == null ? null : command.paymentAction().name());
        payment.put("method", command.paymentMethod() == null ? null : command.paymentMethod().name());
        payment.put("amount", decimal(command.paymentAmount()));
        root.put("payment", payment);
        return root;
    }

    private static String decimal(BigDecimal value) {
        if (value == null) {
            return null;
        }
        if (value.signum() == 0) {
            return "0";
        }
        return value.stripTrailingZeros().toPlainString();
    }

    private byte[] jsonBytes(Object value) {
        try {
            return requestMapper.writeValueAsBytes(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("No se pudo serializar la solicitud de cierre.", ex);
        }
    }

    private static byte[] digest(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 no disponible.", ex);
        }
    }
}
