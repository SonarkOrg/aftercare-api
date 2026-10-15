package com.aftercare.aftercareapi.Services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DocumentServiceFilenameTests {

    @Test
    void stripsC0C1AndBidiControls() {
        assertEquals("report.pdf", DocumentService.sanitizeFilename("re\u0085po\u009Frt‮.pdf"));
        assertEquals("fdp.exe", DocumentService.sanitizeFilename("‮fdp.exe‬"));
        assertEquals("ab.txt", DocumentService.sanitizeFilename("a⁦b⁩⁧.txt⁨"));
        assertEquals("a.pdf", DocumentService.sanitizeFilename("a\u0000\u001F\u007F.pdf"));
    }

    @Test
    void keepsOrdinaryUnicode() {
        assertEquals("résumé é.pdf", DocumentService.sanitizeFilename("dir/résumé é.pdf"));
    }

    @Test
    void blankAfterStrippingFallsBack() {
        assertEquals("document", DocumentService.sanitizeFilename("‮\u0085"));
    }
}
