package com.dentalclinic.financial.api;

import java.net.URI;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dentalclinic.financial.application.FinancialIntentClosureService;
import com.dentalclinic.financial.application.FinancialIntentExecutionService;
import com.dentalclinic.financial.application.FinancialIntentQueryService;
import com.dentalclinic.financial.application.FinancialIntentRegistrationService;
import com.dentalclinic.financial.application.FinancialIntentView;
import com.dentalclinic.financial.application.command.CancelChargeCommand;
import com.dentalclinic.financial.application.command.ConfirmChargeCommand;
import com.dentalclinic.financial.application.command.RegisterPaymentCommand;
import com.dentalclinic.financial.application.command.ReversePaymentCommand;
import com.dentalclinic.financial.domain.FinancialIntentOperation;
import com.dentalclinic.financial.domain.InvalidFinancialIntentRequestException;
import com.dentalclinic.identity.infrastructure.security.CurrentUser;

/** Solo transporte B4. GET nunca delega a un participante de ejecucion/cierre. */
@RestController
@RequestMapping("/api/financial-intents")
public class FinancialIntentController {
    private final FinancialIntentRegistrationService registration;
    private final FinancialIntentExecutionService execution;
    private final FinancialIntentClosureService closures;
    private final FinancialIntentQueryService queries;
    private final FinancialIntentRequestParser parser;

    public FinancialIntentController(FinancialIntentRegistrationService registration, FinancialIntentExecutionService execution,
            FinancialIntentClosureService closures, FinancialIntentQueryService queries, FinancialIntentRequestParser parser) {
        this.registration = registration;
        this.execution = execution;
        this.closures = closures;
        this.queries = queries;
        this.parser = parser;
    }

    @PostMapping
    public ResponseEntity<FinancialIntentView> register(@RequestBody String body, HttpServletRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String alternateKey) {
        parameters(request, Set.of());
        jsonContent(request);
        if (alternateKey != null) throw new InvalidFinancialIntentRequestException(false);
        var parsed = parser.parse(body);
        var actor = CurrentUser.require();
        var outcome = switch (parsed.operation()) {
            case FIN_CONFIRM_CHARGE -> registration.registerWithOutcome((ConfirmChargeCommand) parsed.command(), actor);
            case FIN_REGISTER_PAYMENT -> registration.registerWithOutcome((RegisterPaymentCommand) parsed.command(), actor);
            case FIN_REVERSE_PAYMENT -> registration.registerWithOutcome((ReversePaymentCommand) parsed.command(), actor);
            case FIN_CANCEL_CHARGE -> registration.registerWithOutcome((CancelChargeCommand) parsed.command(), actor);
        };
        var result = queries.detail(parsed.operation(), parsed.intentId(), actor);
        return (outcome.created() ? ResponseEntity.created(URI.create(path(parsed.operation(), parsed.intentId())))
                : ResponseEntity.ok()).body(result);
    }

    @GetMapping("/{operation}/{intentId}")
    public FinancialIntentView detail(@PathVariable FinancialIntentOperation operation, @PathVariable String intentId,
            HttpServletRequest request) {
        parameters(request, Set.of());
        return queries.detail(operation, intentId, CurrentUser.require());
    }

    @GetMapping
    public FinancialIntentView.Page list(@RequestParam String view, @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String cursor, HttpServletRequest request) {
        parameters(request, Set.of("view", "size", "cursor"));
        return queries.list(view, size, cursor, CurrentUser.require());
    }

    @PostMapping("/{operation}/{intentId}/execute")
    public FinancialIntentView execute(@PathVariable FinancialIntentOperation operation, @PathVariable String intentId,
            @RequestBody(required = false) String body, HttpServletRequest request) {
        parameters(request, Set.of());
        if (body != null && !body.isBlank()) jsonContent(request);
        parser.emptyAction(body);
        var actor = CurrentUser.require();
        // Solo verificacion: detecta recibos incompatibles sin intentar reinterpretarlos como negocio.
        // B2 sigue revalidando bajo sus locks; esta lectura no autoriza por si sola la ejecucion.
        queries.detail(operation, intentId, actor);
        switch (operation) {
            case FIN_CONFIRM_CHARGE -> execution.confirmCharge(intentId, actor);
            case FIN_REGISTER_PAYMENT -> execution.registerPayment(intentId, actor);
            case FIN_REVERSE_PAYMENT -> execution.reversePayment(intentId, actor);
            case FIN_CANCEL_CHARGE -> execution.cancelCharge(intentId, actor);
        }
        return queries.detail(operation, intentId, actor);
    }

    @PostMapping("/{operation}/{intentId}/abandon")
    public FinancialIntentView abandon(@PathVariable FinancialIntentOperation operation, @PathVariable String intentId,
            @RequestBody(required = false) String body, HttpServletRequest request) {
        parameters(request, Set.of());
        if (body != null && !body.isBlank()) jsonContent(request);
        parser.emptyAction(body);
        var actor = CurrentUser.require();
        closures.abandon(operation, intentId, actor);
        return queries.detail(operation, intentId, actor);
    }

    private static String path(FinancialIntentOperation operation, String key) {
        return "/api/financial-intents/" + operation.name() + "/" + key;
    }

    private static void parameters(HttpServletRequest request, Set<String> allowed) {
        if (request.getParameterMap().entrySet().stream()
                .anyMatch(entry -> !allowed.contains(entry.getKey()) || entry.getValue().length != 1)) {
            throw new InvalidFinancialIntentRequestException(false);
        }
    }

    private static void jsonContent(HttpServletRequest request) {
        try {
            if (request.getContentType() == null
                    || !MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(request.getContentType()))) {
                throw new InvalidFinancialIntentRequestException(false);
            }
        } catch (IllegalArgumentException invalid) {
            throw new InvalidFinancialIntentRequestException(false);
        }
    }
}
