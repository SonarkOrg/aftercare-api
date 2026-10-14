package com.aftercare.aftercareapi.Questionnaires;

import java.util.Optional;

public enum ItemType {
    BOOLEAN("boolean"), INTEGER("integer"), DECIMAL("decimal"), STRING("string"),
    TEXT("text"), CHOICE("choice"), DISPLAY("display");

    private final String fhir;

    ItemType(String fhir) {
        this.fhir = fhir;
    }

    public static Optional<ItemType> fromFhir(String s) {
        for (ItemType t : values()) {
            if (t.fhir.equals(s)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }
}
