package com.aftercare.aftercareapi.Services;

import com.aftercare.aftercareapi.Models.PatientAccessLink;
import com.aftercare.aftercareapi.Repositories.PatientAccessLinkRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccessLinkServiceTests {

    private final PatientAccessLinkRepository repo = mock(PatientAccessLinkRepository.class);
    private final AccessLinkService service =
            new AccessLinkService(repo, Clock.fixed(Instant.parse("2026-10-14T16:00:00Z"), ZoneId.of("America/Toronto")),
                    new PhiCrypto("dGVzdC1vbmx5LXBoaS1rZXktMzItYnl0ZXMtISEhISE="));

    @Test
    void codesAre43CharsMatchRegexAndUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String c = AccessLinkService.generateCode();
            assertEquals(43, c.length());
            assertTrue(AccessLinkService.CODE_PATTERN.matcher(c).matches());
            assertTrue(seen.add(c));
        }
    }

    @Test
    void sha256OfAbc() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", AccessLinkService.hash("abc"));
    }

    @Test
    void malformedCodeMakesNoRepositoryCall() {
        assertTrue(service.resolve(null).isEmpty());
        assertTrue(service.resolve("short").isEmpty());
        assertTrue(service.resolve("!".repeat(43)).isEmpty());
        verifyNoInteractions(repo);
    }

    @Test
    void revokedCodeResolvesToEmpty() {
        String code = AccessLinkService.generateCode();
        PatientAccessLink link = PatientAccessLink.builder().codeHash(AccessLinkService.hash(code)).revokedAt(Instant.now()).build();
        when(repo.findByCodeHash(AccessLinkService.hash(code))).thenReturn(Optional.of(link));
        assertTrue(service.resolve(code).isEmpty());
        link.setRevokedAt(null);
        assertTrue(service.resolve(code).isPresent());
    }

    @Test
    void unknownCodeResolvesToEmpty() {
        when(repo.findByCodeHash(anyString())).thenReturn(Optional.empty());
        assertTrue(service.resolve(AccessLinkService.generateCode()).isEmpty());
    }
}
