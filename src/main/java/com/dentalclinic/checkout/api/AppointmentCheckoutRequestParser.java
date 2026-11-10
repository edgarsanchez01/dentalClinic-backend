package com.dentalclinic.checkout.api;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.dentalclinic.checkout.api.dto.AppointmentCheckoutRequest;
import com.dentalclinic.checkout.application.CheckoutCommand;
import com.dentalclinic.checkout.domain.ChargeAction;
import com.dentalclinic.checkout.domain.CheckoutErrorCode;
import com.dentalclinic.checkout.domain.CheckoutException;
import com.dentalclinic.checkout.domain.PaymentAction;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.MapperFeature;

/**
 * Parser estricto del unico cuerpo aceptado por el checkout.
 *
 * <p>No reutiliza la indulgencia del mapper HTTP global: rechaza propiedades desconocidas, claves
 * duplicadas y coercion de tipos (por ejemplo {@code "1"} para un ID o {@code "true"} para un
 * booleano). Los IDs e importes del cuerpo no se normalizan: un importe con mas de dos decimales o
 * fuera del rango monetario es una peticion invalida. La clave de idempotencia se valida aparte,
 * sin {@code trim} ni normalizacion.
 */
@Component
public class AppointmentCheckoutRequestParser {

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[\\x21-\\x7E]{1,200}$");
    private static final Pattern CONTEXT_TOKEN = Pattern.compile("^v1:[0-9a-f]{64}$");
    private static final int MAX_INTEGER_DIGITS = 15;
    private static final int BUSINESS_SCALE = 2;

    private final ObjectReader reader;
    private final ObjectReader treeReader;

    public AppointmentCheckoutRequestParser(ObjectMapper mapper) {
        var strict = mapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.reader = strict.readerFor(AppointmentCheckoutRequest.class);
        this.treeReader = strict.readerFor(com.fasterxml.jackson.databind.JsonNode.class);
    }

    public CheckoutCommand parse(Long appointmentId, String idempotencyKey, String body) {
        if (appointmentId == null || appointmentId <= 0) {
            invalid();
        }
        if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            invalid();
        }
        if (body == null || body.isBlank()) {
            invalid();
        }
        AppointmentCheckoutRequest request;
        com.fasterxml.jackson.databind.JsonNode tree;
        try {
            tree = treeReader.readValue(body);
            request = reader.readValue(body);
        } catch (IOException ex) {
            invalid();
            return null;
        }
        if (request == null || request.expectedOccurrenceVersion() == null
                || request.expectedOccurrenceVersion() < 1
                || request.financialContextToken() == null
                || !CONTEXT_TOKEN.matcher(request.financialContextToken()).matches()
                || request.charge() == null || request.payment() == null) {
            invalid();
        }
        var charge = request.charge();
        if (charge.action() == null || charge.expectedTotal() == null
                || !Boolean.TRUE.equals(charge.confirmed())
                || !money(charge.expectedTotal()) || charge.expectedTotal().signum() < 0) {
            invalid();
        }
        if (charge.action() == ChargeAction.CREATE_FROM_APPOINTMENT) {
            if (tree.path("charge").has("chargeId")) {
                invalid();
            }
        } else if (charge.chargeId() == null || charge.chargeId() <= 0) {
            invalid();
        }
        var payment = request.payment();
        if (payment.action() == null) {
            invalid();
        }
        if (payment.action() == PaymentAction.REGISTER) {
            if (payment.method() == null || payment.amount() == null
                    || payment.amount().signum() <= 0 || !money(payment.amount())) {
                invalid();
            }
        } else if (tree.path("payment").has("method") || tree.path("payment").has("amount")) {
            invalid();
        }
        return new CheckoutCommand(appointmentId, request.expectedOccurrenceVersion(),
                request.financialContextToken(), charge.action(), charge.chargeId(),
                charge.expectedTotal(), payment.action(), payment.method(), payment.amount(),
                idempotencyKey);
    }

    private static boolean money(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.scale() > BUSINESS_SCALE) {
            return false;
        }
        return stripped.precision() - stripped.scale() <= MAX_INTEGER_DIGITS;
    }

    private static void invalid() {
        throw new CheckoutException(CheckoutErrorCode.CHECKOUT_INVALID_REQUEST,
                "La peticion de cierre no cumple el contrato.");
    }
}
