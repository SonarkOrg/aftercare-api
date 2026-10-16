package com.aftercare.aftercareapi.Services;

import com.aftercare.aftercareapi.Enums.Specialty;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ActivityTextsTests {

    @Test
    void followUpStartedPluralAndNbspBeforeColon() {
        assertEquals("Suivi postopératoire commencé\u00a0: chirurgie viscérale (1 jour)", ActivityTexts.followUpStarted(Specialty.VISCERAL_SURGERY, 1));
        assertEquals("Suivi postopératoire commencé\u00a0: chirurgie orthopédique (7 jours)", ActivityTexts.followUpStarted(Specialty.ORTHOPEDIC_SURGERY, 7));
    }
}
