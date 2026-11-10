package com.dentalclinic.financial.domain;

/** Estados persistidos. REGISTERED/CONFIRMED son derivados, UNCERTAIN solo es presentacion. */
public enum FinancialIntentDisposition {
    OPEN,
    REJECTED,
    ABANDONED
}
