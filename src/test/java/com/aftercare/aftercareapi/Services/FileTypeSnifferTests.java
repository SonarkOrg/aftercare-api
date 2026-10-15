package com.aftercare.aftercareapi.Services;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileTypeSnifferTests {

    private static byte[] b(int... v) {
        byte[] out = new byte[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (byte) v[i];
        return out;
    }

    @Test
    void acceptedTypes() {
        assertEquals(Optional.of("application/pdf"), FileTypeSniffer.sniff("%PDF-1.7 rest".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(Optional.of("image/jpeg"), FileTypeSniffer.sniff(b(0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10)));
        assertEquals(Optional.of("image/png"), FileTypeSniffer.sniff(b(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0)));
    }

    @Test
    void rejectedTypes() {
        assertEquals(Optional.empty(), FileTypeSniffer.sniff("GIF89a....".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(Optional.empty(), FileTypeSniffer.sniff("....ftypheic".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(Optional.empty(), FileTypeSniffer.sniff("<html><body>".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(Optional.empty(), FileTypeSniffer.sniff(new byte[0]));
        assertEquals(Optional.empty(), FileTypeSniffer.sniff(b(0x41, 0x42, 0x43)));
        assertEquals(Optional.empty(), FileTypeSniffer.sniff(b(0xFF, 0xD8)));
    }
}
