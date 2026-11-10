package com.dentalclinic.financial.application;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.appointment.domain.HttpIdempotency;
import com.dentalclinic.appointment.infrastructure.repository.HttpIdempotencyRepository;
import com.dentalclinic.financial.domain.FinancialConflictException;
import com.dentalclinic.financial.domain.InvalidFinancialMovementException;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

/**
 * Persistencia idempotente de operaciones financieras, reutilizable por una transaccion ya
 * abierta.
 *
 * <p>Conserva exactamente la semantica existente: {@code http_idempotency} con unicidad por
 * actor+operacion+clave, bloqueo asesor por identidad, hash SHA-256 de la solicitud y snapshot
 * JSONB versionado. La clave se consume en la misma transaccion de negocio; no hay reservas
 * anticipadas ni una tabla nueva.
 *
 * <p>Al igual que el nucleo financiero, es {@code package-private}: no autoriza al actor. El
 * llamador debe aplicar su gate antes de {@link #replay} o de cualquier escritura.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class FinancialIdempotencyStore {

    static final String FINANCIAL_NAMESPACE = "dentalclinic-financial";

    private final HttpIdempotencyRepository idempotency;
    private final EntityManager entityManager;
    private final ObjectMapper requestMapper;
    private final ObjectMapper snapshotMapper;

    public FinancialIdempotencyStore(HttpIdempotencyRepository idempotency, EntityManager entityManager,
            ObjectMapper mapper) {
        this.idempotency = idempotency;
        this.entityManager = entityManager;
        this.requestMapper = mapper.copy();
        SimpleModule decimals = new SimpleModule();
        decimals.addSerializer(BigDecimal.class, ToStringSerializer.instance);
        this.snapshotMapper = mapper.copy().registerModule(decimals)
                .setVisibility(PropertyAccessor.ALL, Visibility.NONE)
                .setVisibility(PropertyAccessor.FIELD, Visibility.ANY);
    }

    /**
     * Toma el lock asesor de la identidad y devuelve el hash canonico de la solicitud.
     *
     * @param namespace separa las claves de esta familia de otras operaciones HTTP
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String lockRequest(AuthenticatedUser actor, String namespace, String operation, String key,
            Object command) {
        lockIdentity(actor, namespace, operation, key);
        return requestHash(command);
    }

    /** Mismo lock de siempre, separado del payload para cargar primero el comando durable B2. */
    public void lockIdentity(AuthenticatedUser actor, String namespace, String operation, String key) {
        FinancialTransactionGuard.requireWritable();
        if (key == null || key.isBlank()) {
            throw new InvalidFinancialMovementException(
                    "Idempotency-Key es obligatoria para operaciones financieras.");
        }
        byte[] identity = jsonBytes(List.of(namespace, actor.userId(), operation, key));
        long lockId = ByteBuffer.wrap(digest(identity)).getLong();
        // La proyeccion devuelve INTEGER, no el tipo VOID de pg_advisory_xact_lock.
        entityManager.createNativeQuery("SELECT 1 FROM pg_advisory_xact_lock(:key)")
                .setParameter("key", lockId).getSingleResult();
    }

    public String requestHash(Object command) {
        return HexFormat.of().formatHex(digest(jsonBytes(command)));
    }

    /** Reconstruye tipos existentes, no Map/Double ni otro criterio de hash. */
    public <T> T readCommand(String json, Class<T> type) {
        if (json == null) {
            throw new IllegalStateException("Comando financiero durable ausente.");
        }
        try {
            return requestMapper.readValue(json, type);
        } catch (JsonProcessingException ex) {
            // No exponer JSON/PII en la excepcion ni clasificar el fallo tecnico como REJECTED.
            throw new IllegalStateException("Comando financiero durable incompatible.");
        }
    }

    /** B1: mismo codec de solicitud, sin Map/Double ni envoltorio que cambie el hash existente. */
    public String commandJson(Object command) {
        return new String(jsonBytes(command), StandardCharsets.UTF_8);
    }

    /** B1 no crea ni reproduce recibos; solo evita registrar otra solicitud sobre una clave usada. */
    public boolean hasConflictingReceipt(AuthenticatedUser actor, String operation, String key, String hash) {
        return idempotency.findByIdentity(actor.userId(), operation, key)
                .map(receipt -> !receipt.getRequestHash().equals(hash)).orElse(false);
    }

    /** Un recibo presente bloquea cualquier cierre sin efecto, aunque su snapshot sea ilegible. */
    public boolean hasReceipt(AuthenticatedUser actor, String operation, String key) {
        return idempotency.findByIdentity(actor.userId(), operation, key).isPresent();
    }

    /**
     * Replay de una identidad durable ya bloqueada/verificada. Un recibo presente pero
     * ilegible nunca se trata como permiso para ejecutar otra vez. No reescribe recibos
     * ni cambia el algoritmo/formato historicos de replay o hash.
     */
    public <T> T registeredReplay(AuthenticatedUser actor, String operation, String key, String hash, Class<T> type) {
        if (idempotency.findByIdentity(actor.userId(), operation, key).isEmpty()) {
            return null;
        }
        T result = replay(actor, operation, key, hash, type);
        if (result == null) {
            throw new IllegalStateException("Recibo financiero confirmado ilegible.");
        }
        return result;
    }

    /** Devuelve el snapshot original o {@code null} si la identidad aun no fue usada. */
    @Transactional(propagation = Propagation.MANDATORY)
    public <T> T replay(AuthenticatedUser actor, String operation, String key, String hash, Class<T> type) {
        HttpIdempotency existing = idempotency.findByIdentity(actor.userId(), operation, key).orElse(null);
        if (existing == null) {
            return null;
        }
        if (!existing.getRequestHash().equals(hash)) {
            throw new FinancialConflictException("Idempotency-Key ya fue usada con una solicitud diferente.");
        }
        Map<String, Object> body = existing.getResponseBody();
        if (body == null || !Integer.valueOf(1).equals(body.get("snapshotVersion"))) {
            throw new IllegalStateException("Respuesta idempotente financiera ausente o incompatible.");
        }
        return snapshotMapper.convertValue(body.get("result"), type);
    }

    /** Persiste el snapshot original en la misma transaccion que la operacion de negocio. */
    @Transactional(propagation = Propagation.MANDATORY)
    public <T> T remember(AuthenticatedUser actor, String operation, String key, String hash,
            int status, T result, Class<T> type) {
        FinancialTransactionGuard.requireWritable();
        entityManager.flush();
        entityManager.refresh(result); // Incluye created_at y version reales en el snapshot original.
        Map<String, Object> snapshot = snapshotMapper.convertValue(result, new TypeReference<>() { });
        idempotency.save(HttpIdempotency.create(actor.userId(), operation, key, hash, status,
                Map.of("snapshotVersion", 1, "result", snapshot)));
        entityManager.flush();
        return snapshotMapper.convertValue(snapshot, type);
    }

    private byte[] jsonBytes(Object value) {
        try {
            return requestMapper.writeValueAsBytes(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("No se pudo serializar la solicitud financiera.", ex);
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
