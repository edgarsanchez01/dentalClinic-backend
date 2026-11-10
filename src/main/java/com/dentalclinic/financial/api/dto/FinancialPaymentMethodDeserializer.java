package com.dentalclinic.financial.api.dto;

import java.io.IOException;

import com.dentalclinic.financial.domain.PaymentMethod;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;

/** Los metodos son nombres explicitos, nunca ordinales numericos del enum. */
public class FinancialPaymentMethodDeserializer extends JsonDeserializer<PaymentMethod> {
    @Override
    public PaymentMethod deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() == JsonToken.VALUE_STRING) {
            try {
                return PaymentMethod.valueOf(parser.getText());
            } catch (IllegalArgumentException invalid) {
                // Se traduce abajo al contrato financiero de formato invalido.
            }
        }
        throw InvalidFormatException.from(parser, "Selecciona CASH, CARD, TRANSFER u OTHER.",
                parser.getText(), PaymentMethod.class);
    }
}
