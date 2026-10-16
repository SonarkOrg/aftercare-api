package com.aftercare.aftercareapi.Services.notifications;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RecipientsTests {

    @Test
    void emailsAreTrimmedLowerCasedAndValidated() {
        assertEquals(Optional.of("marie@example.com"), Recipients.email("  Marie@Example.COM "));
        for (String bad : new String[]{null, "", "  ", "no-at-sign", "a@b", "a b@c.de", "@x.ca", "x@.ca"}) {
            assertTrue(Recipients.email(bad).isEmpty(), String.valueOf(bad));
        }
    }

    @Test
    void phonesNormaliseToE164() {
        assertEquals(Optional.of("+15145550100"), Recipients.phoneE164("514-555-0100"));
        assertEquals(Optional.of("+15145550100"), Recipients.phoneE164("(514) 555 0100"));
        assertEquals(Optional.of("+15145550100"), Recipients.phoneE164("1-514-555-0100"));
        assertEquals(Optional.of("+15145550100"), Recipients.phoneE164("15145550100"));
        assertEquals(Optional.of("+15145550100"), Recipients.phoneE164("+1 (514) 555-0100"));
        assertEquals(Optional.of("+442071838750"), Recipients.phoneE164("+44 20 7183 8750"));
        assertEquals(Optional.of("+12345678"), Recipients.phoneE164("+1234 5678"));
        assertEquals(Optional.of("+123456789012345"), Recipients.phoneE164("+123456789012345"));
    }

    @Test
    void badPhonesAreRejected() {
        for (String bad : new String[]{null, "", "555", "555-0100", "514-555-010", "25145550100", "+1234567", "+1234567890123456",
                "+0123456789", "abc", "514-555-01000"}) {
            assertTrue(Recipients.phoneE164(bad).isEmpty(), String.valueOf(bad));
        }
    }

    @Test
    void masks() {
        assertEquals("m•••@example.com", Recipients.maskEmail("marie@example.com"));
        assertEquals("a•••@b.co", Recipients.maskEmail("abc@b.co"));
        assertEquals("•••-•••-0100", Recipients.maskPhone("+15145550100"));
        assertEquals("•••-•••-8750", Recipients.maskPhone("+442071838750"));
        assertFalse(Recipients.maskEmail("marie@example.com").contains("arie"));
    }
}
