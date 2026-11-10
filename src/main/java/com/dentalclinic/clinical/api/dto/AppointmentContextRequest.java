package com.dentalclinic.clinical.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import com.dentalclinic.appointment.application.AppointmentClinicalContextService.Selection;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;

public record AppointmentContextRequest(
        @NotNull @Positive @JsonDeserialize(using = StrictId.class) Long appointmentId,
        @NotNull @Positive @JsonDeserialize(using = StrictVersion.class) Integer occurrenceVersion) {
    public Selection toSelection() { return new Selection(appointmentId, occurrenceVersion); }

    /** Same integer-only transport rule as booking service IDs, scoped to this new payload. */
    public static class StrictId extends JsonDeserializer<Long> {
        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) return (Long) context.handleUnexpectedToken(Long.class, parser);
            return parser.getLongValue();
        }
    }

    public static class StrictVersion extends JsonDeserializer<Integer> {
        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) return (Integer) context.handleUnexpectedToken(Integer.class, parser);
            return parser.getIntValue();
        }
    }
}
