package com.dentalclinic.odontogram.application;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.dentalclinic.odontogram.domain.*;
import com.dentalclinic.odontogram.domain.OdontogramHistoryEvent.Source;
import com.dentalclinic.odontogram.application.OdontogramReconstruction.*;

class OdontogramReconstructionTest {
    private static final Instant T = Instant.parse("2026-01-01T10:00:00Z");
    private final List<ToothMetadata> teeth = List.of(new ToothMetadata(1L, 21, T));

    @Test void ausenciaDeEventosNoEsSanoNiNotasVaciasNiConjuntoVacio() {
        var snapshot = OdontogramReconstruction.at(T, teeth, List.of(), List.of());
        var tooth = snapshot.teeth().getFirst();
        assertThat(tooth.registration()).isEqualTo(Registration.REGISTERED);
        assertThat(tooth.condition().knowledge()).isEqualTo(Knowledge.UNKNOWN);
        assertThat(tooth.notes().knowledge()).isEqualTo(Knowledge.UNKNOWN);
        assertThat(tooth.treatments().knowledge()).isEqualTo(Knowledge.UNKNOWN);
        assertThat(tooth.treatments().treatmentIds()).isNull();
        assertThat(snapshot.completeness().status()).isEqualTo(CompletenessStatus.UNKNOWN);
    }

    @Test void limiteInclusivoYNotasLimpiadasConocidasSonDiferentesDeDesconocidas() {
        var clear = event(Source.NOTES_TOOTH, 1, T, null, null, null);
        var snapshot = OdontogramReconstruction.at(T, teeth, List.of(), List.of(clear,
                event(Source.CONDITION_TOOTH, 2, T.plusNanos(1), ToothCondition.CARIES, null, null)));
        assertThat(snapshot.teeth().getFirst().notes()).isEqualTo(new Value<>(Knowledge.KNOWN, null, T));
        assertThat(snapshot.teeth().getFirst().condition().knowledge()).isEqualTo(Knowledge.UNKNOWN);
    }

    @Test void unValorPrevioNoSeExtiendeAUnInstanteSinEvidencia() {
        var later = event(Source.NOTES_TOOTH, 1, T.plusSeconds(1), null, "Nuevo", null);
        assertThat(OdontogramReconstruction.at(T, teeth, List.of(), List.of(later))
                .teeth().getFirst().notes().knowledge()).isEqualTo(Knowledge.UNKNOWN);
    }

    @Test void addRemoveNoEstablecenQueNoExistanOtrosTratamientos() {
        var snapshot = OdontogramReconstruction.at(T.plusSeconds(2), teeth, List.of(), List.of(
                event(Source.TREATMENT_LINK, 2, T.plusSeconds(1), null, null, TreatmentLinkChange.REMOVE),
                event(Source.TREATMENT_LINK, 1, T, null, null, TreatmentLinkChange.ADD)));
        var treatments = snapshot.teeth().getFirst().treatments();
        assertThat(treatments.knowledge()).isEqualTo(Knowledge.PARTIAL);
        assertThat(treatments.treatmentIds()).isNull();
        assertThat(treatments.knownLinkedIds()).isEmpty();
        assertThat(treatments.knownUnlinkedIds()).containsExactly(9L);
    }

    @Test void ordenTotalDistingueCincoTablasConMismosIdYMismaFecha() {
        var input = List.of(event(Source.NOTES_SURFACE, 1, T, null, "S", null),
                event(Source.TREATMENT_LINK, 1, T, null, null, TreatmentLinkChange.ADD),
                event(Source.NOTES_TOOTH, 1, T, null, "N", null),
                event(Source.CONDITION_SURFACE, 1, T, ToothCondition.RESTORED, null, null),
                event(Source.CONDITION_TOOTH, 1, T, ToothCondition.CROWN, null, null));
        assertThat(input.stream().sorted(OdontogramHistoryEvent.ORDER).map(OdontogramHistoryEvent::source))
                .containsExactly(Source.CONDITION_TOOTH, Source.NOTES_TOOTH, Source.TREATMENT_LINK,
                        Source.CONDITION_SURFACE, Source.NOTES_SURFACE);
        var snapshot = OdontogramReconstruction.at(T, teeth,
                List.of(new SurfaceMetadata(2L, 1L, ToothSurface.MESIAL, T)), input);
        assertThat(snapshot.teeth().getFirst().condition().value()).isEqualTo(ToothCondition.CROWN);
        assertThat(snapshot.teeth().getFirst().surfaces().getFirst().condition().value()).isEqualTo(ToothCondition.RESTORED);
    }

    @Test void registroPosteriorNoPruebaExistenciaAnatomicaNiEstadoAnterior() {
        var tooth = OdontogramReconstruction.at(T.minusSeconds(1), teeth, List.of(), List.of()).teeth().getFirst();
        assertThat(tooth.registration()).isEqualTo(Registration.NOT_REGISTERED);
        assertThat(tooth.condition().knowledge()).isEqualTo(Knowledge.UNKNOWN);
    }

    @Test void eventosDeObservacionesValidanCambioLongitudYAutorSinMutadores() {
        assertThatThrownBy(() -> ToothNotesHistory.create(1L, "Igual", "Igual", 1L, T, null))
                .isInstanceOf(InvalidToothException.class);
        assertThatThrownBy(() -> ToothSurfaceNotesHistory.create(2L, null, "a".repeat(4001), 1L, T, null))
                .isInstanceOf(InvalidToothException.class);
        var clear = ToothNotesHistory.create(1L, "Previo", null, 1L, T, null);
        assertThat(clear.getNewNotes()).isNull();
        assertThat(clear.getPreviousNotes()).isEqualTo("Previo");
        assertThatThrownBy(() -> ToothTreatmentHistory.create(1L, 9L, TreatmentLinkChange.ADD, null, T, null))
                .isInstanceOf(NullPointerException.class);
    }

    private OdontogramHistoryEvent event(Source source, long id, Instant time, ToothCondition condition,
            String notes, TreatmentLinkChange change) {
        boolean surface = source == Source.CONDITION_SURFACE || source == Source.NOTES_SURFACE;
        return new OdontogramHistoryEvent(id, 1L, surface ? 2L : null, 21,
                surface ? ToothSurface.MESIAL : null, source, null, condition, "Previo no fechado", notes,
                change == null ? null : 9L, change, 1L, time, null);
    }
}
