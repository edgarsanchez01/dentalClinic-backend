package com.dentalclinic.appointment.api.dto;

import java.math.BigDecimal;

/** Valores históricos de referencia; no presupuesto, tratamiento ni cargo. */
public record AppointmentServiceSnapshot(Long id, String name, String description,
                                          BigDecimal referencePrice, int durationMinutes) { }
