package com.dentalclinic.financial.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.dentalclinic.financial.domain.FinancialIntentOperation;
import com.dentalclinic.financial.domain.InvalidFinancialIntentRequestException;
import com.dentalclinic.financial.infrastructure.repository.FinancialIntentReadRepository.Position;

class FinancialIntentCursorTest {
    private final Position position = new Position(Instant.parse("2026-10-27T01:02:03.123456Z"),
            "aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa", FinancialIntentOperation.FIN_REGISTER_PAYMENT);

    @Test
    void roundtripConPrecisionDePostgresSinActorComandoNiHash() {
        String value = FinancialIntentCursor.encode("recent", position);
        assertThat(FinancialIntentCursor.decode("recent", value)).isEqualTo(position);
        assertThat(FinancialIntentCursor.decode("recent", null)).isNull();
        String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        assertThat(decoded).isEqualTo("1|recent|2026-10-27T01:02:03.123456Z|aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa|FIN_REGISTER_PAYMENT");
        assertThatThrownBy(() -> FinancialIntentCursor.decode("pending", value)).isInstanceOf(InvalidFinancialIntentRequestException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "%%%", "a", "AA==", "oversized", "version", "timestamp", "uuid", "operation", "extra", "padding"})
    void entradaInvalidaSiempreProduceErrorTipificadoSinValoresDeEntrada(String kind) {
        String raw = switch (kind) {
            case "oversized" -> "a".repeat(301);
            case "version" -> encoded("2|recent|2026-10-27T01:02:03Z|aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa|FIN_REGISTER_PAYMENT");
            case "timestamp" -> encoded("1|recent|secret-notes|aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa|FIN_REGISTER_PAYMENT");
            case "uuid" -> encoded("1|recent|2026-10-27T01:02:03Z|secret-notes|FIN_REGISTER_PAYMENT");
            case "operation" -> encoded("1|recent|2026-10-27T01:02:03Z|aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa|OTHER");
            case "extra" -> FinancialIntentCursor.encode("recent", position) + "extra";
            case "padding" -> FinancialIntentCursor.encode("recent", position) + "=";
            default -> kind;
        };
        assertThatThrownBy(() -> FinancialIntentCursor.decode("recent", raw))
                .isInstanceOf(InvalidFinancialIntentRequestException.class).hasMessageNotContaining("secret-notes");
    }

    private static String encoded(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
