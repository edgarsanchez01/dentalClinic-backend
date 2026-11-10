package com.dentalclinic.odontogram.domain;

/**
 * Notación FDI de dos dígitos (cuadrante + posición) para las piezas dentales (RF-OD-01).
 *
 * <p>Permanente: {@code 11–18}, {@code 21–28}, {@code 31–38}, {@code 41–48}. Temporal:
 * {@code 51–55}, {@code 61–65}, {@code 71–75}, {@code 81–85}. La dentición se deduce de la
 * cuadrante, no se almacena (Architecture.md §11.2).
 */
public final class ToothNotation {

    private ToothNotation() {
    }

    /** @return {@code true} si el código FDI corresponde a una pieza permanente o temporal válida */
    public static boolean isValid(int toothFdi) {
        int quadrant = toothFdi / 10;
        int position = toothFdi % 10;
        return switch (quadrant) {
            case 1, 2, 3, 4 -> position >= 1 && position <= 8;
            case 5, 6, 7, 8 -> position >= 1 && position <= 5;
            default -> false;
        };
    }

    /**
     * Dentición de una pieza a partir de su código FDI.
     *
     * @throws InvalidToothException si el código no es una notación FDI válida
     */
    public static Dentition dentitionOf(int toothFdi) {
        if (!isValid(toothFdi)) {
            throw new InvalidToothException(
                    "La pieza dental " + toothFdi + " no es una notación FDI válida.");
        }
        return toothFdi / 10 <= 4 ? Dentition.PERMANENT : Dentition.TEMPORARY;
    }
}
