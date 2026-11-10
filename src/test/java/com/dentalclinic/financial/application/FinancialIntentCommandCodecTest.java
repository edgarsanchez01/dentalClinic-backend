package com.dentalclinic.financial.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.dentalclinic.appointment.infrastructure.repository.HttpIdempotencyRepository;
import com.dentalclinic.financial.application.command.RegisterPaymentCommand;
import com.dentalclinic.financial.domain.PaymentMethod;
import com.fasterxml.jackson.databind.ObjectMapper;

class FinancialIntentCommandCodecTest {
    @ParameterizedTest
    @ValueSource(strings = {"1E+2", "100", "100.00", "1.00E+2", "1.2300", "0.0100", "1.234E+4", "0E+2", "0.00", "1234567890123456.7890123400"})
    void soloCodecDurableUsaStringsSinCambiarMapperGlobalOHashHistorico(String value) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var historical = new FinancialIdempotencyStore(mock(HttpIdempotencyRepository.class), null, mapper);
        var codec = new FinancialIntentCommandCodec(mapper);
        var command = new RegisterPaymentCommand(42L, PaymentMethod.CARD, new BigDecimal(value), "nota", "identity");
        String originalJson = mapper.writeValueAsString(command);
        String durableJson = codec.encode(command);
        assertThat(mapper.readTree(durableJson).get("amount").isTextual()).isTrue();
        assertThat(mapper.readTree(originalJson).get("amount").isNumber()).isTrue();
        assertThat(historical.commandJson(command)).isEqualTo(originalJson);
        assertThat(mapper.writeValueAsString(command)).isEqualTo(originalJson);
        var restored = historical.readCommand(durableJson, RegisterPaymentCommand.class);
        assertThat(restored).isEqualTo(command);
        String oldHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(originalJson.getBytes(StandardCharsets.UTF_8)));
        assertThat(historical.requestHash(restored)).isEqualTo(oldHash);
        assertThat(historical.requestHash(command)).isEqualTo(oldHash);
    }
}
