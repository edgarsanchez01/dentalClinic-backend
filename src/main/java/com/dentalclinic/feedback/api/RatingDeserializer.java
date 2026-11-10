package com.dentalclinic.feedback.api;

import java.io.IOException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.*;

public class RatingDeserializer extends JsonDeserializer<Integer> {
    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
            throw com.fasterxml.jackson.databind.JsonMappingException.from(parser, "La calificación debe ser un entero de 1 a 5.");
        }
        return parser.getIntValue();
    }
}
