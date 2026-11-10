package com.dentalclinic.financial.application.command;

import java.math.BigDecimal;

/**
 * Concepto de un alta de cargo (contrato de entrada de Fase 2B; no ejecuta nada por si mismo).
 *
 * @param serviceCatalogItemId referencia tecnica OPCIONAL al catalogo; puede ser {@code null}
 * @param treatingDentistId    dentista que presto el concepto; OPCIONAL, no es por si solo fuente
 *                             de autorizacion
 * @param description          descripcion congelada (snapshot historico)
 * @param quantity             cantidad positiva
 * @param unitPrice            precio unitario no negativo
 */
public record ChargeItemData(Long serviceCatalogItemId, Long treatingDentistId, String description,
                             int quantity, BigDecimal unitPrice) {
}
