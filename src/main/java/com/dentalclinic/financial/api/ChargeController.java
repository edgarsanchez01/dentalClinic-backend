package com.dentalclinic.financial.api;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dentalclinic.financial.api.dto.ChargeItemResponse;
import com.dentalclinic.financial.api.dto.ChargeReceiptResponse;
import com.dentalclinic.financial.api.dto.ChargeResponse;
import com.dentalclinic.financial.api.dto.ConfirmChargeRequest;
import com.dentalclinic.financial.api.dto.FinancialMovementResponse;
import com.dentalclinic.financial.api.dto.FinancialReasonRequest;
import com.dentalclinic.financial.api.dto.RegisterPaymentRequest;
import com.dentalclinic.financial.application.ChargeQueryService;
import com.dentalclinic.financial.application.FinancialOperations;
import com.dentalclinic.financial.application.command.CancelChargeCommand;
import com.dentalclinic.financial.application.command.RegisterPaymentCommand;
import com.dentalclinic.financial.application.command.ReversePaymentCommand;
import com.dentalclinic.financial.domain.ChargeStatus;
import com.dentalclinic.identity.infrastructure.security.CurrentUser;
import com.dentalclinic.patient.api.dto.PageResponse;

/** Transporte HTTP exclusivamente: permisos, calculos, transacciones e idempotencia en servicios. */
@RestController
@RequestMapping("/api/charges")
public class ChargeController {
    private final FinancialOperations operations;
    private final ChargeQueryService queries;

    public ChargeController(FinancialOperations operations, ChargeQueryService queries) {
        this.operations = operations;
        this.queries = queries;
    }

    @PostMapping
    public ResponseEntity<ChargeReceiptResponse> confirm(@Valid @RequestBody ConfirmChargeRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        ChargeReceiptResponse result = ChargeReceiptResponse.from(
                operations.confirmCharge(request.toCommand(key), CurrentUser.require()));
        return ResponseEntity.created(URI.create("/api/charges/" + result.id())).body(result);
    }

    @GetMapping
    public PageResponse<ChargeResponse> list(@RequestParam(required = false) @Positive Long patientId,
            @RequestParam(required = false) @Positive Long appointmentId,
            @RequestParam(required = false) ChargeStatus status,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return PageResponse.from(queries.list(patientId, appointmentId, status, from, to, page, size,
                CurrentUser.require()).map(ChargeResponse::summary));
    }

    @GetMapping("/{id}")
    public ChargeResponse detail(@PathVariable @Positive Long id) {
        return ChargeResponse.detail(queries.detail(id, CurrentUser.require()));
    }

    @GetMapping("/{id}/items")
    public List<ChargeItemResponse> items(@PathVariable @Positive Long id) {
        return queries.items(id, CurrentUser.require()).stream().map(ChargeItemResponse::from).toList();
    }

    @GetMapping("/{id}/movements")
    public List<FinancialMovementResponse> movements(@PathVariable @Positive Long id) {
        return queries.movements(id, CurrentUser.require()).stream().map(FinancialMovementResponse::from).toList();
    }

    @PostMapping("/{id}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    public FinancialMovementResponse pay(@PathVariable @Positive Long id,
            @Valid @RequestBody RegisterPaymentRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return FinancialMovementResponse.from(operations.registerPayment(new RegisterPaymentCommand(id,
                request.method(), request.amount(), request.reason(), key), CurrentUser.require()));
    }

    @PostMapping("/{id}/movements/{movementId}/reverse")
    @ResponseStatus(HttpStatus.CREATED)
    public FinancialMovementResponse reverse(@PathVariable @Positive Long id,
            @PathVariable @Positive Long movementId, @Valid @RequestBody FinancialReasonRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return FinancialMovementResponse.from(operations.reversePayment(new ReversePaymentCommand(id,
                movementId, request.reason(), key), CurrentUser.require()));
    }

    @PostMapping("/{id}/cancel")
    public ChargeReceiptResponse cancel(@PathVariable @Positive Long id,
            @Valid @RequestBody FinancialReasonRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return ChargeReceiptResponse.from(operations.cancelCharge(new CancelChargeCommand(id,
                request.reason(), key), CurrentUser.require()));
    }
}
