package com.aftercare.aftercareapi.Services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HealthNumbersTests {

    @Test
    void normalizeUppercasesAndStripsSeparators() {
        assertEquals("TREM68541212", HealthNumbers.normalize("trem 6854-1212"));
        assertEquals("", HealthNumbers.normalize(null));
    }

    @Test
    void validAndInvalidFormats() {
        assertTrue(HealthNumbers.isValid("TREM68541212"));
        assertFalse(HealthNumbers.isValid("TRE68541212"), "3 letters");
        assertFalse(HealthNumbers.isValid("TREM685412123"), "9 digits");
        assertFalse(HealthNumbers.isValid("TREM68131212"), "month 13");
        assertFalse(HealthNumbers.isValid("TREM68631212"), "month 63");
        assertFalse(HealthNumbers.isValid("TREM68540012"), "day 00");
        assertFalse(HealthNumbers.isValid("TREM68543212"), "day 32");
        assertFalse(HealthNumbers.isValid(null));
    }

    @Test
    void femaleMonthRangeIsAccepted() {
        assertTrue(HealthNumbers.isValid("TREM68511212"));
        assertTrue(HealthNumbers.isValid("TREM68621212"));
        assertTrue(HealthNumbers.isValid("TREM68011212"));
        assertFalse(HealthNumbers.isValid("TREM68001212"));
        assertFalse(HealthNumbers.isValid("TREM68501212"));
    }

    @Test
    void mask() {
        assertEquals("TREM •••• ••12", HealthNumbers.mask("TREM68541212"));
    }
}
