package com.dentalclinic.odontogram.domain;

import java.util.Objects;
import jakarta.persistence.*;

@MappedSuperclass
public abstract class NotesHistoryChange extends OdontogramChange {
    @Column(name = "previous_notes", updatable = false, columnDefinition = "TEXT") private String previousNotes;
    @Column(name = "new_notes", updatable = false, columnDefinition = "TEXT") private String newNotes;

    protected void change(String previous, String next) {
        if (Objects.equals(previous, next)) throw new InvalidToothException("Las observaciones no cambiaron.");
        if ((previous != null && previous.length() > 4000) || (next != null && next.length() > 4000)) {
            throw new InvalidToothException("Las observaciones admiten hasta 4000 caracteres.");
        }
        previousNotes = previous;
        newNotes = next;
    }
    public String getPreviousNotes() { return previousNotes; }
    public String getNewNotes() { return newNotes; }
}
