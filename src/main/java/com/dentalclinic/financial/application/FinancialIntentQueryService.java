package com.dentalclinic.financial.application;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.financial.domain.Charge;
import com.dentalclinic.financial.domain.FinancialIntentDisposition;
import com.dentalclinic.financial.domain.FinancialIntentOperation;
import com.dentalclinic.financial.domain.FinancialIntentCommandConflictException;
import com.dentalclinic.financial.domain.FinancialIntentReceiptIncompatibleException;
import com.dentalclinic.financial.domain.FinancialIntentState;
import com.dentalclinic.financial.domain.FinancialMovementType;
import com.dentalclinic.financial.domain.FinancialRejectionReason;
import com.dentalclinic.financial.domain.InvalidFinancialIntentRequestException;
import com.dentalclinic.financial.infrastructure.repository.ChargeRepository;
import com.dentalclinic.financial.infrastructure.repository.FinancialIntentReadRepository;
import com.dentalclinic.financial.infrastructure.repository.FinancialIntentReadRepository.Row;
import com.dentalclinic.financial.infrastructure.repository.FinancialMovementRepository;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.exception.NotFoundException;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

/** B4 exclusivamente de lectura. Nunca usa execute/replayRegistered ni participantes de escritura. */
@Service
@Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, readOnly = true)
public class FinancialIntentQueryService {
    private final FinancialIntentReadRepository repository;
    private final FinancialAuthorizationService authorization;
    private final ChargeRepository charges;
    private final FinancialMovementRepository movements;
    private final ObjectReader json;

    public FinancialIntentQueryService(FinancialIntentReadRepository repository, FinancialAuthorizationService authorization,
            ChargeRepository charges, FinancialMovementRepository movements, ObjectMapper mapper) {
        this.repository = repository;
        this.authorization = authorization;
        this.charges = charges;
        this.movements = movements;
        this.json = mapper.readerFor(JsonNode.class);
    }

    public FinancialIntentView detail(FinancialIntentOperation operation, String key, AuthenticatedUser actor) {
        authorization.requireIntentRegistration(actor, operation);
        FinancialIntentRegistrationService.requireCanonicalKey(key);
        return view(repository.find(actor.userId(), operation, key).orElseThrow(NotFoundException::new), actor);
    }

    public FinancialIntentView.Page list(String filter, int size, String cursor, AuthenticatedUser actor) {
        authorization.requireIntentRegistration(actor, FinancialIntentOperation.FIN_CONFIRM_CHARGE);
        if (!("pending".equals(filter) || "recent".equals(filter)) || size < 1 || size > 50) {
            throw new InvalidFinancialIntentRequestException(false);
        }
        var position = FinancialIntentCursor.decode(filter, cursor);
        var rows = repository.page(actor.userId(), actor.role() == Role.OWNER, "pending".equals(filter), position, size + 1);
        boolean more = rows.size() > size;
        List<Row> page = more ? rows.subList(0, size) : rows;
        var items = page.stream().map(row -> view(row, actor)).toList();
        return new FinancialIntentView.Page(items, more ? FinancialIntentCursor.encode(filter, page.getLast().position()) : null);
    }

    private FinancialIntentView view(Row row, AuthenticatedUser actor) {
        authorization.requireIntentRegistration(actor, row.operation());
        if (!FinancialIntentCommandCodec.supports(row.commandVersion()) || row.hashVersion() != 1) {
            throw new FinancialIntentCommandConflictException();
        }
        FinancialIntentView.Effect effect = null;
        FinancialIntentView.Rejection rejection = null;
        FinancialIntentState state;
        if (row.receiptId() != null) {
            if (row.disposition() != FinancialIntentDisposition.OPEN || !Objects.equals(row.hash(), row.receiptHash())) {
                throw incompatible();
            }
            effect = effect(row, actor);
            state = FinancialIntentState.CONFIRMED;
        } else {
            state = switch (row.disposition()) {
                case OPEN -> FinancialIntentState.REGISTERED;
                case ABANDONED -> FinancialIntentState.ABANDONED;
                case REJECTED -> FinancialIntentState.REJECTED;
            };
            if (state == FinancialIntentState.REJECTED) {
                // Nunca reflejar texto libre de BD como codigo publico.
                var reason = FinancialRejectionReason.valueOf(row.rejectionCode());
                if (reason.operation() != row.operation() || !Objects.equals(row.rejectionStatus(), reason.httpStatus())) {
                    throw new IllegalStateException("Metadata de rechazo incompatible.");
                }
                rejection = new FinancialIntentView.Rejection(reason.name(), reason.httpStatus());
            }
        }
        return new FinancialIntentView(row.operation(), row.key(), state, row.registeredAt(), row.closedAt(),
                row.attemptedAt(), rejection, effect);
    }

    /** Solo proyeccion del recibo original; no reserializa ni reescribe recibos historicos. */
    private FinancialIntentView.Effect effect(Row row, AuthenticatedUser actor) {
        JsonNode body;
        try {
            body = row.receiptBody() == null ? null : json.readValue(row.receiptBody());
        } catch (IOException failure) { throw incompatible(); }
        int expectedStatus = row.operation() == FinancialIntentOperation.FIN_CANCEL_CHARGE ? 200 : 201;
        if (!Objects.equals(row.receiptStatus(), expectedStatus) || body == null
                || !body.path("snapshotVersion").isIntegralNumber() || !body.path("snapshotVersion").canConvertToInt()
                || body.path("snapshotVersion").intValue() != 1
                || !body.path("result").isObject()) throw incompatible();
        JsonNode result = body.path("result");
        long id = positiveId(result, "id");
        Charge charge;
        Long movementId = null;
        if (row.operation() == FinancialIntentOperation.FIN_REGISTER_PAYMENT
                || row.operation() == FinancialIntentOperation.FIN_REVERSE_PAYMENT) {
            var movement = movements.findById(id).orElseThrow(FinancialIntentQueryService::incompatible);
            var expectedType = row.operation() == FinancialIntentOperation.FIN_REGISTER_PAYMENT
                    ? FinancialMovementType.PAYMENT : FinancialMovementType.REVERSAL;
            if (movement.getType() != expectedType || !expectedType.name().equals(result.path("type").asText())
                    || !Objects.equals(movement.getPerformedByUserId(), actor.userId())
                    || positiveId(result, "performedByUserId") != actor.userId()
                    || positiveId(result, "chargeId") != movement.getChargeId()
                    || positiveId(result, "patientId") != movement.getPatientId()) throw incompatible();
            try {
                if (!result.path("amount").isTextual()
                        || new BigDecimal(result.path("amount").textValue()).compareTo(movement.getAmount()) != 0) throw incompatible();
            } catch (NumberFormatException failure) { throw incompatible(); }
            charge = charges.findById(movement.getChargeId()).orElseThrow(FinancialIntentQueryService::incompatible);
            if (!Objects.equals(charge.getPatientId(), movement.getPatientId())) throw incompatible();
            movementId = id;
        } else {
            charge = charges.findById(id).orElseThrow(FinancialIntentQueryService::incompatible);
            boolean cancel = row.operation() == FinancialIntentOperation.FIN_CANCEL_CHARGE;
            String author = cancel ? "cancelledByUserId" : "confirmedByUserId";
            Long actualAuthor = cancel ? charge.getCancelledByUserId() : charge.getConfirmedByUserId();
            if (!Objects.equals(actualAuthor, actor.userId()) || positiveId(result, author) != actor.userId()
                    || positiveId(result, "patientId") != charge.getPatientId()
                    || !matchesInstant(result.path(cancel ? "cancelledAt" : "confirmedAt"),
                            cancel ? charge.getCancelledAt() : charge.getConfirmedAt())) throw incompatible();
        }
        authorization.requireView(actor, charge);
        if (row.operation() == FinancialIntentOperation.FIN_REGISTER_PAYMENT) authorization.requireRegisterPayment(actor, charge);
        return new FinancialIntentView.Effect(charge.getId(), movementId);
    }

    private static long positiveId(JsonNode value, String name) {
        JsonNode id = value.path(name);
        if (!id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() <= 0) throw incompatible();
        return id.longValue();
    }

    private static boolean matchesInstant(JsonNode value, Instant expected) {
        if (!value.isTextual() || expected == null) return false;
        try { return expected.equals(Instant.parse(value.textValue())); }
        catch (java.time.format.DateTimeParseException invalid) { return false; }
    }

    private static FinancialIntentReceiptIncompatibleException incompatible() {
        return new FinancialIntentReceiptIncompatibleException();
    }
}
