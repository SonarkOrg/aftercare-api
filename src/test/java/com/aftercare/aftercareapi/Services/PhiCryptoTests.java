package com.aftercare.aftercareapi.Services;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class PhiCryptoTests {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void roundTrip() {
        PhiCrypto c = new PhiCrypto(KEY);
        String enc = c.encrypt("TREM68541212", 7);
        assertTrue(enc.startsWith("v1:"));
        assertFalse(enc.contains("TREM68541212"));
        assertEquals("TREM68541212", c.decrypt(enc, 7));
    }

    @Test
    void differentPatientIdFails() {
        PhiCrypto c = new PhiCrypto(KEY);
        String enc = c.encrypt("TREM68541212", 7);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> c.decrypt(enc, 8));
        assertFalse(e.getMessage().contains("TREM"));
    }

    @Test
    void ivIsRandom() {
        PhiCrypto c = new PhiCrypto(KEY);
        assertNotEquals(c.encrypt("TREM68541212", 7), c.encrypt("TREM68541212", 7));
    }

    @Test
    void badKeysFailAtConstruction() {
        assertThrows(IllegalStateException.class, () -> new PhiCrypto(""));
        assertThrows(IllegalStateException.class, () -> new PhiCrypto(null));
        assertThrows(IllegalStateException.class, () -> new PhiCrypto(Base64.getEncoder().encodeToString(new byte[16])));
        assertThrows(IllegalStateException.class, () -> new PhiCrypto("not base64 !!"));
    }
}
