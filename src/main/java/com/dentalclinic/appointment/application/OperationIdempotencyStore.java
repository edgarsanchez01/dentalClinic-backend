package com.dentalclinic.appointment.application;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
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
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;
import com.dentalclinic.scheduling.domain.ScheduleConflictException;
import com.dentalclinic.scheduling.domain.exception.InvalidScheduleException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

/** Durable, versioned command receipts. Authorization belongs to each caller, before replay.
 * No expiry: pruning these rows would remove the durable deduplication guarantee. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class OperationIdempotencyStore {
    private final HttpIdempotencyRepository records;
    private final EntityManager em;
    private final ObjectMapper mapper;

    public OperationIdempotencyStore(HttpIdempotencyRepository records, EntityManager em,
            ObjectMapper mapper) {
        this.records = records;
        this.em = em;
        SimpleModule decimals = new SimpleModule();
        decimals.addSerializer(BigDecimal.class, ToStringSerializer.instance);
        this.mapper = mapper.copy().registerModule(decimals)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    public String lock(AuthenticatedUser actor, String operation, String key, Object request) {
        if (key == null || !key.matches("[\\x21-\\x7E]{1,200}")) {
            throw new InvalidScheduleException("Idempotency-Key debe contener 1–200 caracteres ASCII sin espacios.");
        }
        long lock = ByteBuffer.wrap(hash(bytes(List.of("dentalclinic-ux05c", actor.userId(),
                operation, key)))).getLong();
        em.createNativeQuery("SELECT 1 FROM pg_advisory_xact_lock(:key)")
                .setParameter("key", lock).getSingleResult();
        return HexFormat.of().formatHex(hash(bytes(Map.of("requestCanonicalizationVersion", 1, "request", request))));
    }

    public <T> T replay(AuthenticatedUser actor, String operation, String key, String hash,
            Class<T> type) {
        var previous = records.findByIdentity(actor.userId(), operation, key).orElse(null);
        if (previous == null) return null;
        if (!previous.getRequestHash().equals(hash)) {
            throw new ScheduleConflictException("Idempotency-Key ya fue usada con otra solicitud.");
        }
        var body = previous.getResponseBody();
        if (body == null || !Integer.valueOf(1).equals(body.get("snapshotVersion"))
                || !Integer.valueOf(1).equals(body.get("requestCanonicalizationVersion"))
                || body.get("result") == null) {
            throw new IllegalStateException("Respuesta idempotente ausente o incompatible.");
        }
        return mapper.convertValue(body.get("result"), type);
    }

    public void remember(AuthenticatedUser actor, String operation, String key, String hash,
            Object result) {
        em.flush();
        Map<String, Object> snapshot = mapper.convertValue(result, new TypeReference<>() { });
        records.save(HttpIdempotency.create(actor.userId(), operation, key, hash, 200,
                Map.of("snapshotVersion", 1, "requestCanonicalizationVersion", 1, "result", snapshot)));
        em.flush();
    }

    private byte[] bytes(Object value) {
        try { return mapper.writeValueAsBytes(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("No se pudo serializar la operacion.", ex); }
    }

    private static byte[] hash(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
