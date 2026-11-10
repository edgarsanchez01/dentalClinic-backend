package com.dentalclinic.odontogram.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Superficie de una pieza dental (RF-OD-02, PRD §19.1, Architecture.md §11.2).
 *
 * <p>Conjunto canónico anatómico acordado para F7.4.3.2 (los documentos literalmente se
 * contradecían: PRD §19.1 «5 en anteriores: M, D/V, L, B, P» vs Architecture §11.2 «5 en
 * anteriores (Mesial, Distal/Vestibular, Lingual, Bucal, Palatina)»). El modelo aquí es:
 *
 * <ul>
 *   <li><strong>Anteriores</strong> (incisivos y caninos, posición FDI 1–3, permanentes y
 *       temporales): MESIAL, DISTAL, VESTIBULAR, LINGUAL, INCISAL.</li>
 *   <li><strong>Posteriores</strong> (premolares y molares, posición FDI 4–8): MESIAL, DISTAL,
 *       VESTIBULAR, LINGUAL, OCCLUSAL.</li>
 * </ul>
 *
 * <p>Cada pieza usa una única cara de cierre, {@code INCISAL} (anterior) u {@code OCCLUSAL}
 * (posterior). Las otras cuatro son comunes. La validez por grupo se resuelve aquí, en dominio,
 * porque requiere la posición FDI de la pieza (la base de datos no puede hacerlo con un CHECK:
 * habría que unir con {@link ToothRecord}).
 */
public enum ToothSurface {
    MESIAL,
    DISTAL,
    VESTIBULAR,
    LINGUAL,
    OCCLUSAL,
    INCISAL;

    private static final Set<ToothSurface> ANTERIOR =
            EnumSet.of(MESIAL, DISTAL, VESTIBULAR, LINGUAL, INCISAL);
    private static final Set<ToothSurface> POSTERIOR =
            EnumSet.of(MESIAL, DISTAL, VESTIBULAR, LINGUAL, OCCLUSAL);

    /**
     * Punto de corte por posición FDI: 1–3 anteriores, 4–8 posteriores. Vale para ambas
     * denticiones (las temporales llegan a posición 5).
     */
    private static boolean isAnterior(int toothFdi) {
        return toothFdi % 10 <= 3;
    }

    /**
     * @return {@code true} si esta superficie existe en el grupo del diente.
     * @throws InvalidToothException si el código FDI no es una notación válida
     */
    public boolean isValidFor(int toothFdi) {
        return allowedFor(toothFdi).contains(this);
    }

    /**
     * Superficies válidas de una pieza según su grupo.
     *
     * @throws InvalidToothException si el código FDI no es una notación válida
     */
    public static Set<ToothSurface> allowedFor(int toothFdi) {
        if (!ToothNotation.isValid(toothFdi)) {
            throw new InvalidToothException(
                    "La pieza dental " + toothFdi + " no es una notación FDI válida.");
        }
        return isAnterior(toothFdi) ? ANTERIOR : POSTERIOR;
    }
}