package com.dentalclinic.financial.api;

import java.io.IOException;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;
import com.dentalclinic.financial.application.CollectionCommand;
import com.dentalclinic.financial.application.ContextualCheckoutFinancialParticipant.*;
import com.dentalclinic.financial.domain.PaymentMethod;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;

/** Strict collection contract, independent of the retired checkout's durable identity. */
@Component
public class CollectionRequestParser {
    private final ObjectReader reader;
    private final ObjectReader treeReader;

    public CollectionRequestParser(ObjectMapper mapper) {
        var strict = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        reader = strict.readerFor(Request.class);
        treeReader = strict.readerFor(JsonNode.class);
    }

    public CollectionCommand parse(Long id, String key, String body) {
        Request request;
        JsonNode tree;
        try {
            if (body == null || body.isBlank()) { CollectionCommand.invalid(); }
            tree = treeReader.readValue(body);
            request = reader.readValue(body);
        } catch (IOException error) { CollectionCommand.invalid(); return null; }
        if (request == null || request.expectedOccurrenceVersion() == null || request.charge() == null
                || request.payment() == null || !Boolean.TRUE.equals(request.charge().confirmed())) {
            CollectionCommand.invalid();
        }
        var charge = request.charge();
        var payment = request.payment();
        if (charge.action() == ChargeAction.CREATE_FROM_APPOINTMENT && tree.path("charge").has("chargeId")) {
            CollectionCommand.invalid();
        }
        if (payment.action() == PaymentAction.NO_NEW_PAYMENT
                && (tree.path("payment").has("method") || tree.path("payment").has("amount"))) {
            CollectionCommand.invalid();
        }
        return new CollectionCommand(id, request.expectedOccurrenceVersion(),
                new ChargeInstruction(charge.action(), charge.chargeId(), charge.expectedTotal(), request.financialContextToken()),
                new PaymentInstruction(payment.action(), payment.method(), payment.amount()), key);
    }

    public record Request(Integer expectedOccurrenceVersion, String financialContextToken,
            ChargeRequest charge, PaymentRequest payment) { }
    public record ChargeRequest(ChargeAction action, Long chargeId, BigDecimal expectedTotal, Boolean confirmed) { }
    public record PaymentRequest(PaymentAction action, PaymentMethod method, BigDecimal amount) { }
}
