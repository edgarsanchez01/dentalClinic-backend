package com.dentalclinic.financial.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.dentalclinic.financial.application.command.ConfirmChargeCommand;
import com.dentalclinic.financial.domain.InvalidFinancialIntentRequestException;
import com.fasterxml.jackson.databind.ObjectMapper;

class FinancialIntentRequestParserTest {
    private static final ValidatorFactory VALIDATION = Validation.buildDefaultValidatorFactory();
    private final ObjectMapper global = new ObjectMapper();
    private final FinancialIntentRequestParser parser = new FinancialIntentRequestParser(global, VALIDATION.getValidator());
    private static final String KEY = "aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa";

    @AfterAll static void closeValidator() { VALIDATION.close(); }

    @ParameterizedTest
    @ValueSource(strings = {"1E+2", "0.10", "100.00", "1.00E+2"})
    void decimalesAnidadosYTextosNoSeNormalizan(String price) {
        String command = "{\"patientId\":1,\"notes\":\"  nota  \",\"items\":[{\"description\":\"  concepto  \",\"quantity\":2,\"unitPrice\":" + price + "}]}";
        var parsed = parser.parse(envelope("FIN_CONFIRM_CHARGE", command));
        var actual = (ConfirmChargeCommand) parsed.command();
        assertThat(actual.items().getFirst().unitPrice()).isEqualTo(new BigDecimal(price));
        assertThat(actual.items().getFirst().description()).isEqualTo("  concepto  ");
        assertThat(actual.notes()).isEqualTo("  nota  ");
        assertThat(actual.idempotencyKey()).isEqualTo(KEY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"patientId\":1,\"items\":[]}",
            "{\"patientId\":1,\"items\":[null]}",
            "{\"patientId\":1,\"notes\":123,\"items\":[{\"description\":\"ok\",\"quantity\":1,\"unitPrice\":1}]}",
            "{\"patientId\":1,\"items\":[{\"description\":true,\"quantity\":1,\"unitPrice\":1}]}",
            "{\"patientId\":1,\"items\":[{\"description\":\"ok\",\"quantity\":1.5,\"unitPrice\":1}]}",
            "{\"patientId\":1,\"items\":[{\"description\":\"ok\",\"quantity\":2147483648,\"unitPrice\":1}]}"})
    void validaDtoExistenteYRechazaCoercionesAntesDeRegistrar(String command) {
        assertThatThrownBy(() -> parser.parse(envelope("FIN_CONFIRM_CHARGE", command)))
                .isInstanceOf(InvalidFinancialIntentRequestException.class);
    }

    @Test
    void parserLocalNoModificaMapperHistorico() throws Exception {
        parser.parse(envelope("FIN_CANCEL_CHARGE", "{\"chargeId\":1,\"reason\":\"motivo\"}"));
        assertThat(global.writeValueAsString(new BigDecimal("1E+2"))).isEqualTo("1E+2");
        assertThat(global.isEnabled(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)).isFalse();
    }

    private static String envelope(String operation, String command) {
        return "{\"operation\":\"" + operation + "\",\"intentId\":\"" + KEY + "\",\"command\":" + command + "}";
    }
}
