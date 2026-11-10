package com.dentalclinic.odontogram.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Reglas de superficies por grupo de diente (RF-OD-02, F7.4.3.2).
 *
 * <p>La matriz de validez acordada: anteriores (posición FDI 1–3, permanentes y temporales) = M,
 * D, V, L, I; posteriores (posición FDI 4–8) = M, D, V, L, O. La cara de cierre es INCISAL en
 * anteriores y OCCLUSAL en posteriores; el resto es común.
 */
class ToothSurfaceTest {

    @Test
    void anterioresPermanentes_permitenIncisalYNoOclusal() {
        for (int anterior : new int[] {11, 21, 31, 41, 13, 23, 33, 43}) {
            assertThat(ToothSurface.INCISAL.isValidFor(anterior)).as("INCISAL en %d", anterior).isTrue();
            assertThat(ToothSurface.OCCLUSAL.isValidFor(anterior)).as("OCCLUSAL en %d", anterior).isFalse();
        }
    }

    @Test
    void posterioresPermanentes_permitenOclusalYNoIncisal() {
        for (int posterior : new int[] {16, 24, 38, 45, 48}) {
            assertThat(ToothSurface.OCCLUSAL.isValidFor(posterior)).as("OCCLUSAL en %d", posterior).isTrue();
            assertThat(ToothSurface.INCISAL.isValidFor(posterior)).as("INCISAL en %d", posterior).isFalse();
        }
    }

    @Test
    void temporales_usanLaMismaReglaPorPosicion() {
        assertThat(ToothSurface.allowedFor(51)).isEqualTo(
                Set.of(ToothSurface.MESIAL, ToothSurface.DISTAL, ToothSurface.VESTIBULAR,
                        ToothSurface.LINGUAL, ToothSurface.INCISAL));
        assertThat(ToothSurface.allowedFor(65)).isEqualTo(
                Set.of(ToothSurface.MESIAL, ToothSurface.DISTAL, ToothSurface.VESTIBULAR,
                        ToothSurface.LINGUAL, ToothSurface.OCCLUSAL));
        assertThat(ToothSurface.INCISAL.isValidFor(51)).isTrue();
        assertThat(ToothSurface.OCCLUSAL.isValidFor(85)).isTrue();
        assertThat(ToothSurface.INCISAL.isValidFor(85)).isFalse();
    }

    @Test
    void superficiesComunes_sonValidasEnAmbosGrupos() {
        for (int tooth : new int[] {11, 16, 51, 85}) {
            for (ToothSurface common : new ToothSurface[] {ToothSurface.MESIAL,
                    ToothSurface.DISTAL, ToothSurface.VESTIBULAR, ToothSurface.LINGUAL}) {
                assertThat(common.isValidFor(tooth)).as("%s en %d", common, tooth).isTrue();
            }
        }
    }

    @Test
    void fdiInvalido_rechazado() {
        assertThatThrownBy(() -> ToothSurface.allowedFor(19))
                .isInstanceOf(InvalidToothException.class);
        assertThatThrownBy(() -> ToothSurface.OCCLUSAL.isValidFor(56))
                .isInstanceOf(InvalidToothException.class);
    }
}