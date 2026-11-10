package com.dentalclinic.feedback.infrastructure;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Reproducible solo con clave externa: la outbox no necesita persistir tokens en claro/cifrados. */
@Component
public class FeedbackTokens {
    private final byte[] key;

    public FeedbackTokens(@Value("${dentalclinic.feedback.token-secret:${dentalclinic.security.jwt.secret}}") String secret) {
        if (secret.length() < 32) throw new IllegalArgumentException("La clave de feedback requiere 32 caracteres.");
        this.key = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String token(Long appointmentId, Instant createdAt) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] data = ("DentalClinic:patient-feedback:v1:" + appointmentId + ":" + createdAt)
                    .getBytes(StandardCharsets.UTF_8);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(data));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo preparar la invitación.");
        }
    }
}
