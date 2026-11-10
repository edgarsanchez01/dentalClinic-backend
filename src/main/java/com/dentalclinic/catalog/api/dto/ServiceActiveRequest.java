package com.dentalclinic.catalog.api.dto;

import jakarta.validation.constraints.NotNull;

public record ServiceActiveRequest(@NotNull(message = "Indica si el servicio está activo.") Boolean active) { }
