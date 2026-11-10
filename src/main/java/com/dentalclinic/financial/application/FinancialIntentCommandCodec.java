package com.dentalclinic.financial.application;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

/**
 * Codec DURABLE, independiente del hash historico de solicitudes.
 * V1: JSON numerico de B1 (puede haber perdido representacion al pasar por JSONB).
 * V2: BigDecimal.toString() como string JSON; preserva unscaledValue y scale,
 * incluida notacion exponencial. No modifica el mapper global ni el requestMapper.
 */
@Component
class FinancialIntentCommandCodec {
    static final int CURRENT_VERSION = 2;
    private final ObjectMapper mapper;

    FinancialIntentCommandCodec(ObjectMapper mapper) {
        var decimals = new SimpleModule();
        decimals.addSerializer(BigDecimal.class, ToStringSerializer.instance);
        this.mapper = mapper.copy().registerModule(decimals);
    }

    public String encode(Object command) {
        try {
            return mapper.writeValueAsString(command);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("No se pudo codificar el comando financiero durable.");
        }
    }

    static boolean supports(int version) {
        return version == 1 || version == CURRENT_VERSION;
    }
}
