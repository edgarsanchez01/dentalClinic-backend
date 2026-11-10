package com.dentalclinic.financial.api;

import java.io.IOException;
import java.math.BigDecimal;

import jakarta.validation.Validator;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.stereotype.Component;

import com.dentalclinic.financial.api.dto.ConfirmChargeRequest;
import com.dentalclinic.financial.api.dto.FinancialIntegerDeserializer;
import com.dentalclinic.financial.api.dto.FinancialPaymentMethodDeserializer;
import com.dentalclinic.financial.api.dto.StrictFinancialRequest;
import com.dentalclinic.financial.application.command.CancelChargeCommand;
import com.dentalclinic.financial.application.command.RegisterPaymentCommand;
import com.dentalclinic.financial.application.command.ReversePaymentCommand;
import com.dentalclinic.financial.domain.FinancialIntentOperation;
import com.dentalclinic.financial.domain.InvalidFinancialIntentRequestException;
import com.dentalclinic.financial.domain.PaymentMethod;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.type.LogicalType;

/** Parser local estricto, sin modificar mapper global ni codec/hash historicos. */
@Component
public class FinancialIntentRequestParser {
    private final ObjectMapper mapper;
    private final Validator validator;

    public FinancialIntentRequestParser(ObjectMapper mapper, Validator validator) {
        this.mapper = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
        this.mapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        this.validator = validator;
    }

    public Parsed parse(String body) {
        try {
            Request request = mapper.readValue(body, Request.class);
            validate(request);
            if (!request.command().isObject()) throw invalid();
            Object command = switch (request.operation()) {
                case FIN_CONFIRM_CHARGE -> convert(request.command(), ConfirmChargeRequest.class).toCommand(request.intentId());
                case FIN_REGISTER_PAYMENT -> {
                    var payment = convert(request.command(), Payment.class);
                    yield new RegisterPaymentCommand(payment.chargeId(), payment.method(), payment.amount(), payment.reason(), request.intentId());
                }
                case FIN_REVERSE_PAYMENT -> {
                    var reverse = convert(request.command(), Reverse.class);
                    yield new ReversePaymentCommand(reverse.chargeId(), reverse.movementId(), reverse.reason(), request.intentId());
                }
                case FIN_CANCEL_CHARGE -> {
                    var cancel = convert(request.command(), Cancel.class);
                    yield new CancelChargeCommand(cancel.chargeId(), cancel.reason(), request.intentId());
                }
            };
            return new Parsed(request.operation(), request.intentId(), command);
        } catch (IOException | IllegalArgumentException error) { throw invalid(); }
    }

    public void emptyAction(String body) {
        if (body == null || body.isBlank()) return;
        try {
            JsonNode value = mapper.readTree(body);
            if (value == null || !value.isObject() || !value.isEmpty()) throw invalid();
        } catch (IOException error) { throw invalid(); }
    }

    private <T> T convert(JsonNode value, Class<T> type) throws IOException {
        T request = mapper.treeToValue(value, type);
        validate(request);
        return request;
    }

    private void validate(Object request) {
        if (request == null || !validator.validate(request).isEmpty()) throw invalid();
    }

    private static InvalidFinancialIntentRequestException invalid() { return new InvalidFinancialIntentRequestException(false); }

    public record Parsed(FinancialIntentOperation operation, String intentId, Object command) {
        @Override public String toString() { return "FinancialIntentRequest[operation=" + operation + "]"; }
    }

    public record Request(@NotNull FinancialIntentOperation operation, @NotBlank String intentId,
            @NotNull JsonNode command) implements StrictFinancialRequest { }

    public record Payment(@NotNull @Positive @JsonDeserialize(using = FinancialIntegerDeserializer.class) Long chargeId,
            @NotNull @JsonDeserialize(using = FinancialPaymentMethodDeserializer.class) PaymentMethod method,
            @NotNull @Positive @Digits(integer = 15, fraction = 2) BigDecimal amount, String reason) implements StrictFinancialRequest { }

    public record Reverse(@NotNull @Positive @JsonDeserialize(using = FinancialIntegerDeserializer.class) Long chargeId,
            @NotNull @Positive @JsonDeserialize(using = FinancialIntegerDeserializer.class) Long movementId,
            @NotBlank String reason) implements StrictFinancialRequest { }

    public record Cancel(@NotNull @Positive @JsonDeserialize(using = FinancialIntegerDeserializer.class) Long chargeId,
            @NotBlank String reason) implements StrictFinancialRequest { }
}
