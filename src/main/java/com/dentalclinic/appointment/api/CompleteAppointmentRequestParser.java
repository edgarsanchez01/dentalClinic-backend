package com.dentalclinic.appointment.api;

import java.io.IOException;
import org.springframework.stereotype.Component;
import com.dentalclinic.appointment.api.dto.CompleteAppointmentRequest;
import com.dentalclinic.scheduling.domain.exception.InvalidScheduleException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;

@Component
public class CompleteAppointmentRequestParser {
    private final ObjectReader reader;
    public CompleteAppointmentRequestParser(ObjectMapper mapper) {
        reader = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .readerFor(CompleteAppointmentRequest.class);
    }
    public CompleteAppointmentRequest parse(String body) {
        try {
            CompleteAppointmentRequest request = reader.readValue(body);
            if (request == null || request.expectedOccurrenceVersion() == null || request.expectedOccurrenceVersion() < 1) invalid();
            return request;
        } catch (IOException ex) { invalid(); return null; }
    }
    private static void invalid() { throw new InvalidScheduleException("Indica expectedOccurrenceVersion como entero positivo, sin otros campos."); }
}
