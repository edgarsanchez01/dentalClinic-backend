package com.dentalclinic.financial.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import org.springframework.stereotype.Service;

import com.dentalclinic.financial.application.AppointmentFinancialContextService.FinancialContextCharge;
import com.dentalclinic.financial.domain.Charge;
import com.dentalclinic.financial.domain.ChargeFinancialState;
import com.dentalclinic.financial.domain.ChargeItem;
import com.dentalclinic.financial.domain.ChargeStatusCalculator;
import com.dentalclinic.financial.domain.FinancialContextIntegrity;
import com.dentalclinic.financial.domain.FinancialContextUnavailableException;
import com.dentalclinic.financial.domain.FinancialMovement;
import com.dentalclinic.financial.domain.InvalidChargeException;
import com.dentalclinic.financial.infrastructure.repository.ChargeItemRepository;
import com.dentalclinic.financial.infrastructure.repository.FinancialMovementRepository;
import com.dentalclinic.identity.domain.exception.ForbiddenOperationException;
import com.dentalclinic.identity.domain.exception.NotFoundException;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;

/**
 * Verificacion e hidratacion compartida del conjunto financiero de una atencion.
 *
 * <p>La usan la lectura contextual (Etapa 2) y el participante financiero del checkout (Etapa 3)
 * para que ambos apliquen exactamente las mismas invariantes y produzcan los mismos valores. No
 * abre transaccion, no bloquea filas y no autoriza la cita: el llamador decide bajo que locks se
 * cargan los cargos y conserva su propia frontera. Cada cargo se valida de nuevo contra la cita y
 * contra el actor antes de entrar en el conjunto.
 */
@Service
class FinancialContextAssembler {

    private final ChargeItemRepository items;
    private final FinancialMovementRepository movements;
    private final FinancialAuthorizationService authorization;

    FinancialContextAssembler(ChargeItemRepository items, FinancialMovementRepository movements,
            FinancialAuthorizationService authorization) {
        this.items = items;
        this.movements = movements;
        this.authorization = authorization;
    }

    /**
     * Carga conceptos y movimientos de todos los cargos y verifica el conjunto completo. Un cargo
     * no verificable aborta el conjunto con {@link FinancialContextUnavailableException}; nunca se
     * devuelve una parte.
     */
    List<FinancialContextCharge> verifyAll(Long appointmentId, Long patientId,
            List<Charge> associated, AuthenticatedUser actor) {
        if (associated.isEmpty()) {
            return List.of();
        }
        List<Long> chargeIds = associated.stream().map(Charge::getId).toList();
        Map<Long, List<ChargeItem>> itemsByCharge = groupByCharge(
                items.findByChargeIdInOrderByChargeIdAscIdAsc(chargeIds), ChargeItem::getChargeId);
        List<FinancialMovement> allMovements = movements
                .findByChargeIdInOrderByChargeIdAscPerformedAtAscIdAsc(chargeIds);
        Map<Long, List<FinancialMovement>> movementsByCharge = groupByCharge(allMovements,
                FinancialMovement::getChargeId);
        Map<Long, List<FinancialMovement>> externalReversalsByTarget = groupByCharge(
                loadExternalReversals(allMovements), FinancialMovement::getReversesMovementId);

        List<FinancialContextCharge> contextCharges = new ArrayList<>(associated.size());
        for (Charge charge : associated) {
            List<ChargeItem> chargeItems = itemsByCharge.getOrDefault(charge.getId(), List.of());
            List<FinancialMovement> chargeMovements = movementsByCharge.getOrDefault(charge.getId(),
                    List.of());
            requireVerifiable(actor, appointmentId, patientId, charge);
            FinancialContextIntegrity.requireChargeItems(charge.getId(), charge.getTotalAmount(),
                    chargeItems);
            BigDecimal applied = FinancialContextIntegrity.requireAppliedAmount(charge.getId(),
                    charge.getPatientId(),
                    verifiedMovements(chargeMovements, externalReversalsByTarget));
            ChargeFinancialState state = deriveState(charge, applied);
            contextCharges.add(new FinancialContextCharge(charge, state, chargeItems,
                    chargeMovements));
        }
        return List.copyOf(contextCharges);
    }

    private List<FinancialMovement> loadExternalReversals(List<FinancialMovement> movementsOfCharges) {
        List<Long> movementIds = movementsOfCharges.stream().map(FinancialMovement::getId)
                .filter(Objects::nonNull).toList();
        if (movementIds.isEmpty()) {
            return List.of();
        }
        return movements.findByReversesMovementIdInOrderByReversesMovementIdAscIdAsc(movementIds);
    }

    private static List<FinancialMovement> verifiedMovements(List<FinancialMovement> own,
            Map<Long, List<FinancialMovement>> externalReversalsByTarget) {
        Map<Long, FinancialMovement> union = new LinkedHashMap<>();
        for (FinancialMovement movement : own) {
            union.put(movement.getId(), movement);
        }
        for (FinancialMovement movement : own) {
            for (FinancialMovement external : externalReversalsByTarget.getOrDefault(
                    movement.getId(), List.of())) {
                union.putIfAbsent(external.getId(), external);
            }
        }
        return List.copyOf(union.values());
    }

    private void requireVerifiable(AuthenticatedUser actor, Long appointmentId, Long patientId,
            Charge charge) {
        if (charge.getId() == null
                || !appointmentId.equals(charge.getAppointmentId())
                || !patientId.equals(charge.getPatientId())) {
            throw new FinancialContextUnavailableException();
        }
        try {
            authorization.requireView(actor, charge);
        } catch (NotFoundException | ForbiddenOperationException denied) {
            throw new FinancialContextUnavailableException();
        }
    }

    private static ChargeFinancialState deriveState(Charge charge, BigDecimal applied) {
        try {
            return ChargeStatusCalculator.evaluate(charge.getTotalAmount(), applied,
                    charge.isCancelled());
        } catch (InvalidChargeException inconsistent) {
            throw new FinancialContextUnavailableException();
        }
    }

    private static <T> Map<Long, List<T>> groupByCharge(List<T> values,
            Function<T, Long> chargeId) {
        Map<Long, List<T>> grouped = new LinkedHashMap<>();
        for (T value : values) {
            grouped.computeIfAbsent(chargeId.apply(value), key -> new ArrayList<>()).add(value);
        }
        return grouped;
    }
}
