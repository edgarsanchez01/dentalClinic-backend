package com.dentalclinic.financial.application;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.dentalclinic.appointment.domain.Appointment;
import com.dentalclinic.appointment.domain.AppointmentStatus;
import com.dentalclinic.financial.application.AppointmentFinancialContextService.FinancialContext;
import com.dentalclinic.financial.application.AppointmentFinancialContextService.FinancialContextCharge;
import com.dentalclinic.financial.domain.Charge;
import com.dentalclinic.financial.domain.ChargeItem;
import com.dentalclinic.financial.domain.FinancialMovement;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Constructor unico del token de contexto financiero versionado ({@code v1}).
 *
 * <p>Serializa una representacion canonica explicita, no entidades por reflexion: claves de objeto
 * ordenadas, listas ordenadas por ID, decimales en cadena plana sin ceros sobrantes, instantes UTC
 * normalizados a microsegundos y {@code null} explicito. Excluye {@code observedAt}, la hora actual,
 * la clave HTTP y el orden accidental de las consultas. La version {@code v1} fija los campos; un
 * cambio de la lista o de la normalizacion exige otra version y no puede reinterpretar tokens ya
 * emitidos.
 *
 * <p>No es un secreto ni una autorizacion: conocerlo no concede acceso. Es una precondicion de
 * frescura que el checkout recalcula bajo sus locks antes de escribir.
 */
@Component
public class FinancialContextTokenBuilder {

    public static final String VERSION = "v1";

    private final ObjectMapper canonical;

    public FinancialContextTokenBuilder(ObjectMapper mapper) {
        this.canonical = mapper.copy()
                .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    public String tokenFor(FinancialContext context) {
        return tokenFor(AppointmentState.from(context.appointment()), context.charges());
    }

    public String tokenFor(Appointment appointment, List<FinancialContextCharge> charges) {
        return tokenFor(AppointmentState.from(appointment), charges);
    }

    public String tokenFor(AppointmentState appointment, List<FinancialContextCharge> charges) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", VERSION);
        root.put("appointment", appointmentNode(appointment));
        List<Map<String, Object>> orderedCharges = charges.stream()
                .sorted(Comparator.comparing(context -> context.charge().getId()))
                .map(FinancialContextTokenBuilder::chargeNode)
                .toList();
        root.put("charges", orderedCharges);
        return VERSION + ":" + sha256Hex(write(root));
    }

    private static Map<String, Object> appointmentNode(AppointmentState appointment) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", appointment.id());
        node.put("patientId", appointment.patientId());
        node.put("dentistId", appointment.dentistId());
        node.put("status", appointment.status() == null ? null : appointment.status().name());
        node.put("occurrenceVersion", appointment.occurrenceVersion());
        node.put("startAt", instant(appointment.startAt()));
        node.put("endAt", instant(appointment.endAt()));
        node.put("durationMinutes", appointment.durationMinutes());
        Map<String, Object> service = new LinkedHashMap<>();
        service.put("id", appointment.serviceId());
        service.put("name", appointment.serviceName());
        service.put("description", appointment.serviceDescription());
        service.put("referencePrice", decimal(appointment.serviceReferencePrice()));
        service.put("durationMinutes", appointment.serviceDurationMinutes());
        node.put("service", service);
        return node;
    }

    private static Map<String, Object> chargeNode(FinancialContextCharge context) {
        Charge charge = context.charge();
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", charge.getId());
        node.put("patientId", charge.getPatientId());
        node.put("appointmentId", charge.getAppointmentId());
        node.put("currency", charge.getCurrency());
        node.put("notes", charge.getNotes());
        node.put("totalAmount", decimal(charge.getTotalAmount()));
        node.put("confirmedAt", instant(charge.getConfirmedAt()));
        node.put("confirmedByUserId", charge.getConfirmedByUserId());
        node.put("cancelledAt", instant(charge.getCancelledAt()));
        node.put("cancelledByUserId", charge.getCancelledByUserId());
        node.put("cancellationReason", charge.getCancellationReason());
        node.put("version", charge.getVersion());
        node.put("createdAt", instant(charge.getCreatedAt()));

        List<Map<String, Object>> orderedItems = new ArrayList<>(context.items().size());
        context.items().stream().sorted(Comparator.comparing(ChargeItem::getId)).forEach(item ->
                orderedItems.add(itemNode(item)));
        node.put("items", orderedItems);

        List<Map<String, Object>> orderedMovements = new ArrayList<>(context.movements().size());
        context.movements().stream().sorted(Comparator.comparing(FinancialMovement::getId))
                .forEach(movement -> orderedMovements.add(movementNode(movement)));
        node.put("movements", orderedMovements);
        return node;
    }

    private static Map<String, Object> itemNode(ChargeItem item) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", item.getId());
        node.put("chargeId", item.getChargeId());
        node.put("serviceCatalogItemId", item.getServiceCatalogItemId());
        node.put("treatingDentistId", item.getTreatingDentistId());
        node.put("description", item.getDescription());
        node.put("quantity", item.getQuantity());
        node.put("unitPrice", decimal(item.getUnitPrice()));
        node.put("lineTotal", decimal(item.getLineTotal()));
        node.put("createdAt", instant(item.getCreatedAt()));
        return node;
    }

    private static Map<String, Object> movementNode(FinancialMovement movement) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", movement.getId());
        node.put("chargeId", movement.getChargeId());
        node.put("patientId", movement.getPatientId());
        node.put("type", movement.getType() == null ? null : movement.getType().name());
        node.put("direction", movement.getDirection() == null ? null : movement.getDirection().name());
        node.put("paymentMethod", movement.getPaymentMethod() == null ? null
                : movement.getPaymentMethod().name());
        node.put("amount", decimal(movement.getAmount()));
        node.put("reason", movement.getReason());
        node.put("reversesMovementId", movement.getReversesMovementId());
        node.put("performedByUserId", movement.getPerformedByUserId());
        node.put("performedAt", instant(movement.getPerformedAt()));
        node.put("createdAt", instant(movement.getCreatedAt()));
        return node;
    }

    private static String decimal(BigDecimal value) {
        if (value == null) {
            return null;
        }
        if (value.signum() == 0) {
            return "0";
        }
        return value.stripTrailingZeros().toPlainString();
    }

    private static String instant(Instant value) {
        return value == null ? null : value.truncatedTo(ChronoUnit.MICROS).toString();
    }

    private byte[] write(Object value) {
        try {
            return canonical.writeValueAsBytes(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("No se pudo serializar el contexto financiero.", ex);
        }
    }

    private static String sha256Hex(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 no disponible.", ex);
        }
    }

    /** Vista estable de la cita; los campos opcionales del snapshot se conservan como null. */
    public record AppointmentState(Long id, Long patientId, Long dentistId,
            AppointmentStatus status, int occurrenceVersion, Instant startAt, Instant endAt,
            int durationMinutes, Long serviceId, String serviceName, String serviceDescription,
            BigDecimal serviceReferencePrice, Integer serviceDurationMinutes) {

        public static AppointmentState from(Appointment appointment) {
            return new AppointmentState(appointment.getId(), appointment.getPatientId(),
                    appointment.getDentistId(), appointment.getStatus(),
                    appointment.getOccurrenceVersion(), appointment.getStartAt(),
                    appointment.getEndAt(), appointment.getDurationMinutes(),
                    appointment.getServiceId(), appointment.getServiceName(),
                    appointment.getServiceDescription(), appointment.getServiceReferencePrice(),
                    appointment.getServiceDurationMinutes());
        }
    }
}
