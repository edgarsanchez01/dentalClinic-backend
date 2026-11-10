package com.dentalclinic.financial.domain;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Verificacion de integridad del contexto financiero de una atencion (UX-05B.1, Etapa 2).
 *
 * <p>La vista contextual solo puede devolverse si el conjunto es <strong>integramente coherente</strong>:
 * un cargo, sus conceptos y sus movimientos. Este verificador aplica las invariantes que el esquema
 * no puede expresar con constraints simples (suma de conceptos, paciente de cada movimiento,
 * referencia de una reversa a un pago del <strong>mismo</strong> cargo, reversa completa, etc.).
 *
 * <p>No corrige ni normaliza datos: si algo no cuadra, lanza
 * {@link FinancialContextUnavailableException} para que la capa HTTP responda un {@code 409}
 * generico. No persiste nada y no crea reglas de cobro nuevas: reutiliza las factories y los
 * importes del dominio existente.
 */
public final class FinancialContextIntegrity {

    private FinancialContextIntegrity() {
    }

    /**
     * Verifica los conceptos de un cargo y que su suma coincida <strong>exactamente</strong> con el
     * total confirmado.
     *
     * <p>Se aplica a <strong>todos</strong> los roles, no solo al {@code DENTIST}: un cargo sin
     * conceptos o con un total incoherente no es un contexto verificable para nadie.
     */
    public static void requireChargeItems(Long chargeId, BigDecimal chargeTotal, List<ChargeItem> items) {
        if (chargeId == null || chargeTotal == null || items == null || items.isEmpty()) {
            throw new FinancialContextUnavailableException();
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (ChargeItem item : items) {
            if (item == null || !chargeId.equals(item.getChargeId())
                    || item.getDescription() == null || item.getDescription().isBlank()
                    || item.getLineTotal() == null) {
                throw new FinancialContextUnavailableException();
            }
            try {
                BigDecimal expectedLineTotal = ChargeItem.calculateLineTotal(item.getQuantity(), item.getUnitPrice());
                if (expectedLineTotal.compareTo(item.getLineTotal()) != 0) {
                    throw new FinancialContextUnavailableException();
                }
                // Misma precision monetaria que el alta del concepto.
                FinancialAmounts.requireNonNegative(item.getLineTotal(), "lineTotal",
                        message -> new FinancialContextUnavailableException());
            } catch (InvalidChargeException invalid) {
                throw new FinancialContextUnavailableException();
            }
            sum = sum.add(item.getLineTotal());
        }
        FinancialAmounts.requirePositive(chargeTotal, "totalAmount",
                message -> new FinancialContextUnavailableException());
        if (sum.compareTo(chargeTotal) != 0) {
            throw new FinancialContextUnavailableException();
        }
    }

    /**
     * Verifica los movimientos del cargo y devuelve el monto aplicado calculado <strong>solo</strong>
     * con pagos integros y no revertidos.
     *
     * <p>Invariantes comprobadas:
     *
     * <ul>
     *   <li>cada movimiento pertenece al cargo y al paciente consultados;</li>
     *   <li>cada importe es positivo y respeta la precision monetaria del dominio;</li>
     *   <li>un {@code PAYMENT} tiene direccion {@code IN}, metodo de pago y no referencia otra
     *       reversa; una {@code REVERSAL} tiene direccion {@code OUT}, motivo y referencia un pago;</li>
     *   <li>la reversa apunta a un {@code PAYMENT} del mismo cargo y paciente, por su importe
     *       <strong>completo</strong>, y no hay dos reversas del mismo pago;</li>
     *   <li>el saldo se deriva de esa lista verificada, nunca de una agregacion que pudiera incluir
     *       movimientos incoherentes.</li>
     * </ul>
     *
     * <p>El llamador puede incluir reversas <strong>externas</strong> que apunten a pagos de este
     * cargo: se rechazan porque una reversa valida debe pertenecer al mismo cargo.
     *
     * @return suma de pagos validos (no revertidos), nunca negativa
     */
    public static BigDecimal requireAppliedAmount(Long chargeId, Long patientId,
                                                  List<FinancialMovement> movements) {
        if (chargeId == null || patientId == null || movements == null) {
            throw new FinancialContextUnavailableException();
        }
        Map<Long, FinancialMovement> byId = new HashMap<>();
        for (FinancialMovement movement : movements) {
            requireValidShape(chargeId, patientId, movement);
            if (byId.putIfAbsent(movement.getId(), movement) != null) {
                throw new FinancialContextUnavailableException();
            }
        }

        Set<Long> reversedPayments = new HashSet<>();
        for (FinancialMovement movement : movements) {
            if (movement.getType() != FinancialMovementType.REVERSAL) {
                continue;
            }
            Long reversedId = movement.getReversesMovementId();
            FinancialMovement original = byId.get(reversedId);
            if (original == null
                    || original.getType() != FinancialMovementType.PAYMENT
                    || !chargeId.equals(original.getChargeId())
                    || !patientId.equals(original.getPatientId())
                    || original.getReversesMovementId() != null
                    || movement.getAmount().compareTo(original.getAmount()) != 0
                    || !reversedPayments.add(reversedId)) {
                throw new FinancialContextUnavailableException();
            }
        }

        BigDecimal applied = BigDecimal.ZERO;
        for (FinancialMovement movement : movements) {
            if (movement.getType() == FinancialMovementType.PAYMENT
                    && !reversedPayments.contains(movement.getId())) {
                applied = applied.add(movement.getAmount());
            }
        }
        return applied;
    }

    private static void requireValidShape(Long chargeId, Long patientId, FinancialMovement movement) {
        if (movement == null || movement.getId() == null || movement.getType() == null
                || movement.getDirection() == null
                || !chargeId.equals(movement.getChargeId())
                || !patientId.equals(movement.getPatientId())) {
            throw new FinancialContextUnavailableException();
        }
        FinancialAmounts.requirePositive(movement.getAmount(), "amount",
                message -> new FinancialContextUnavailableException());
        switch (movement.getType()) {
            case PAYMENT -> {
                if (movement.getDirection() != MovementDirection.IN
                        || movement.getPaymentMethod() == null
                        || movement.getReversesMovementId() != null) {
                    throw new FinancialContextUnavailableException();
                }
            }
            case REVERSAL -> {
                if (movement.getDirection() != MovementDirection.OUT
                        || movement.getPaymentMethod() != null
                        || movement.getReversesMovementId() == null
                        || movement.getReason() == null || movement.getReason().isBlank()) {
                    throw new FinancialContextUnavailableException();
                }
            }
        }
    }
}
