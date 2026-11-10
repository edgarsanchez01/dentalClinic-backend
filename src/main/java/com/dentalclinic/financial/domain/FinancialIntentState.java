package com.dentalclinic.financial.domain;

/** Estado publico derivado, nunca columna ni snapshot persistido. */
public enum FinancialIntentState {
    REGISTERED, CONFIRMED, REJECTED, ABANDONED
}
