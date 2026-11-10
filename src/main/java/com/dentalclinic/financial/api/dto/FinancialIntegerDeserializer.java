package com.dentalclinic.financial.api.dto;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;

/** IDs/cantidades JSON enteros: no trunca decimales ni convierte booleanos o strings. */
public class FinancialIntegerDeserializer extends JsonDeserializer<Long> {
    @Override
    public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
            throw InvalidFormatException.from(parser, "Se requiere un numero entero JSON.", parser.getText(), Long.class);
        }
        return parser.getLongValue();
    }
}
