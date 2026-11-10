package com.dentalclinic.financial.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import com.dentalclinic.financial.domain.FinancialIntentOperation;
import com.dentalclinic.financial.domain.InvalidFinancialIntentRequestException;
import com.dentalclinic.financial.infrastructure.repository.FinancialIntentReadRepository.Position;

/** Posicion, no credencial. Contiene solo datos publicos; SQL siempre impone actor/rol. */
final class FinancialIntentCursor {
    private FinancialIntentCursor() { }

    static String encode(String view, Position position) {
        String value = String.join("|", "1", view, position.at().toString(), position.key(), position.operation().name());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    static Position decode(String view, String cursor) {
        if (cursor == null) return null;
        try {
            if (cursor.isEmpty() || cursor.length() > 300) throw new IllegalArgumentException();
            String[] fields = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", -1);
            if (fields.length != 5 || !"1".equals(fields[0]) || !view.equals(fields[1])) throw new IllegalArgumentException();
            FinancialIntentRegistrationService.requireCanonicalKey(fields[3]);
            Position position = new Position(Instant.parse(fields[2]), fields[3], FinancialIntentOperation.valueOf(fields[4]));
            if (!encode(view, position).equals(cursor)) throw new IllegalArgumentException();
            return position;
        } catch (RuntimeException invalid) {
            throw new InvalidFinancialIntentRequestException(true);
        }
    }
}
