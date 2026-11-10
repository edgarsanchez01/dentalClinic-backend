package com.dentalclinic.odontogram.application;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.odontogram.domain.ToothRecord;
import com.dentalclinic.odontogram.domain.TreatmentTooth;
import com.dentalclinic.odontogram.infrastructure.repository.ToothRecordRepository;
import com.dentalclinic.odontogram.infrastructure.repository.TreatmentToothRepository;

/**
 * Consulta de lectura de las piezas vinculadas a tratamientos (RF-OD-05, T2 de F7.4.4).
 *
 * <p>Es la dirección inversa del vínculo {@code treatment_tooth}: dado un conjunto de tratamientos
 * del paciente, devuelve los códigos FDI de las piezas registradas que tratan. Sirve al módulo
 * clínico para componer las respuestas de tratamientos sin acceder a repositorios de odontograma.
 *
 * <p>Evita N+1: resuelve todos los tratamientos solicitados con dos consultas por lote (vínculos y
 * piezas). El aislamiento por paciente se garantiza filtrando las piezas por {@code patientId}, de
 * modo que nunca se exponen asociaciones de otro paciente.
 */
@Service
public class TreatmentToothLinkService {

    private final TreatmentToothRepository treatmentToothRepository;
    private final ToothRecordRepository toothRecordRepository;

    public TreatmentToothLinkService(TreatmentToothRepository treatmentToothRepository,
                                     ToothRecordRepository toothRecordRepository) {
        this.treatmentToothRepository = treatmentToothRepository;
        this.toothRecordRepository = toothRecordRepository;
    }

    /**
     * Números FDI de las piezas del paciente vinculadas a cada tratamiento, ordenados de forma
     * ascendente y sin duplicados. Los tratamientos sin piezas no aparecen en el mapa.
     */
    @Transactional(readOnly = true)
    public Map<Long, List<Integer>> toothFdisByTreatmentIds(Long patientId,
                                                            Collection<Long> treatmentIds) {
        if (patientId == null || treatmentIds == null || treatmentIds.isEmpty()) {
            return Map.of();
        }
        List<TreatmentTooth> links = treatmentToothRepository.findByTreatmentIdIn(treatmentIds);
        if (links.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> fdiByRecordId = toothRecordRepository
                .findByPatientIdAndIdIn(patientId,
                        links.stream().map(TreatmentTooth::getToothRecordId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(ToothRecord::getId,
                        ToothRecord::getToothFdi, (first, second) -> first));
        Map<Long, List<Integer>> fdisByTreatment = new HashMap<>();
        for (TreatmentTooth link : links) {
            Integer fdi = fdiByRecordId.get(link.getToothRecordId());
            if (fdi == null) {
                continue;
            }
            fdisByTreatment.computeIfAbsent(link.getTreatmentId(), key -> new ArrayList<>()).add(fdi);
        }
        Map<Long, List<Integer>> result = new HashMap<>();
        fdisByTreatment.forEach((treatmentId, fdis) -> result.put(treatmentId,
                fdis.stream().distinct().sorted().toList()));
        return result;
    }
}
