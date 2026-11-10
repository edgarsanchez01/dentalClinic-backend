package com.dentalclinic.financial.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.financial.domain.Charge;
import com.dentalclinic.financial.domain.ChargeFinancialState;
import com.dentalclinic.financial.domain.ChargeItem;
import com.dentalclinic.financial.domain.ChargeStatus;
import com.dentalclinic.financial.domain.ChargeStatusCalculator;
import com.dentalclinic.financial.domain.FinancialMovement;
import com.dentalclinic.financial.domain.InvalidChargeException;
import com.dentalclinic.financial.infrastructure.repository.ChargeItemRepository;
import com.dentalclinic.financial.infrastructure.repository.ChargeRepository;
import com.dentalclinic.financial.infrastructure.repository.FinancialMovementRepository;
import com.dentalclinic.identity.domain.exception.NotFoundException;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;

/** Lecturas autorizadas. REPEATABLE_READ mantiene pagina/conteo/saldos e historial coherentes. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ChargeQueryService {
    private final ChargeRepository charges;
    private final ChargeItemRepository items;
    private final FinancialMovementRepository movements;
    private final FinancialAuthorizationService authorization;
    private final ChargeFinancialStateService states;

    public ChargeQueryService(ChargeRepository charges, ChargeItemRepository items,
            FinancialMovementRepository movements, FinancialAuthorizationService authorization,
            ChargeFinancialStateService states) {
        this.charges = charges;
        this.items = items;
        this.movements = movements;
        this.authorization = authorization;
        this.states = states;
    }

    public Page<ChargeView> list(Long patientId, Long appointmentId, ChargeStatus status,
            Instant from, Instant to, int page, int size, AuthenticatedUser actor) {
        var scope = authorization.listScope(actor);
        if (from != null && to != null && !from.isBefore(to)) {
            throw new InvalidChargeException("El inicio del intervalo debe ser anterior al fin.");
        }
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        if (!scope.global() && scope.dentistId() == null) {
            return Page.empty(pageable); // Sin perfil profesional, nunca se convierte en alcance global.
        }
        Page<Charge> result = charges.findVisible(patientId, appointmentId, scope.dentistId(),
                status == null ? null : status.name(), from, to, pageable);
        Map<Long, BigDecimal> amounts = result.isEmpty() ? Map.of() : movements.appliedAmounts(
                result.getContent().stream().map(Charge::getId).toList()).stream()
                .collect(Collectors.toMap(FinancialMovementRepository.AppliedAmount::getChargeId,
                        FinancialMovementRepository.AppliedAmount::getAmount));
        return result.map(charge -> new ChargeView(charge, ChargeStatusCalculator.evaluate(
                charge.getTotalAmount(), amounts.getOrDefault(charge.getId(), BigDecimal.ZERO), charge.isCancelled())));
    }

    public ChargeDetail detail(Long chargeId, AuthenticatedUser actor) {
        Charge charge = authorizedCharge(chargeId, actor);
        return new ChargeDetail(new ChargeView(charge, states.stateOf(chargeId, actor)),
                items.findByChargeIdOrderByIdAsc(chargeId), movements.findByChargeIdOrderByPerformedAtAscIdAsc(chargeId));
    }

    public List<ChargeItem> items(Long chargeId, AuthenticatedUser actor) {
        authorizedCharge(chargeId, actor);
        return items.findByChargeIdOrderByIdAsc(chargeId);
    }

    public List<FinancialMovement> movements(Long chargeId, AuthenticatedUser actor) {
        authorizedCharge(chargeId, actor);
        return movements.findByChargeIdOrderByPerformedAtAscIdAsc(chargeId);
    }

    private Charge authorizedCharge(Long chargeId, AuthenticatedUser actor) {
        Charge charge = charges.findById(chargeId).orElseThrow(NotFoundException::new);
        authorization.requireView(actor, charge);
        return charge;
    }

    public record ChargeView(Charge charge, ChargeFinancialState financialState) { }
    public record ChargeDetail(ChargeView view, List<ChargeItem> items, List<FinancialMovement> movements) { }
}
