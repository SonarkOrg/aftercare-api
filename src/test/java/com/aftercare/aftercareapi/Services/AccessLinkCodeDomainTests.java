package com.aftercare.aftercareapi.Services;

import com.aftercare.aftercareapi.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** Stored link codes are domain-separated from other PHI ciphertexts and verified against their hash. */
class AccessLinkCodeDomainTests extends IntegrationTest {

    @Autowired AccessLinkService links;
    @Autowired PhiCrypto crypto;

    private long followUpWithLink(long patient) throws Exception {
        return newFollowUp(patient, "VISCERAL_SURGERY", "2026-10-13", 7).get("followUp").get("id").asLong();
    }

    @Test
    void theIssuedCodeStillRoundTrips() throws Exception {
        long p = newPatient();
        JsonNode f = newFollowUp(p, "VISCERAL_SURGERY", "2026-10-13", 7);
        Optional<String> code = links.currentCodeForFollowUp(f.get("followUp").get("id").asLong());
        assertEquals(f.get("accessCode").asText(), code.orElseThrow());
    }

    @Test
    void aHealthNumberCiphertextCopiedIntoCodeEncYieldsNothing() throws Exception {
        long p = newPatient();
        assertEquals(200, call(put("/api/patients/" + p + "/health-number").contentType(MediaType.APPLICATION_JSON)
                .content("{\"healthNumber\":\"trem 6854-1212\"}")).getResponse().getStatus());
        long fu = followUpWithLink(p);
        assertTrue(links.currentCodeForFollowUp(fu).isPresent());
        jdbc.update("update patient_access_links set code_enc = (select health_number_enc from patients where id = ?) where follow_up_id = ?", p, fu);
        assertTrue(links.currentCodeForFollowUp(fu).isEmpty());
    }

    @Test
    void aCiphertextOfTheOldPatientAadIsNotAcceptedAsALinkCode() throws Exception {
        long p = newPatient();
        JsonNode f = newFollowUp(p, "VISCERAL_SURGERY", "2026-10-13", 7);
        long fu = f.get("followUp").get("id").asLong();
        jdbc.update("update patient_access_links set code_enc = ? where follow_up_id = ?",
                crypto.encrypt(f.get("accessCode").asText(), p), fu);
        assertTrue(links.currentCodeForFollowUp(fu).isEmpty());
    }

    @Test
    void aWellFormedCodeThatDoesNotMatchTheLinkHashYieldsNothing() throws Exception {
        long p = newPatient();
        long fu = followUpWithLink(p);
        String other = AccessLinkService.generateCode();
        jdbc.update("update patient_access_links set code_enc = ? where follow_up_id = ?",
                crypto.encryptWithContext(other, "access-link:" + p), fu);
        assertTrue(links.currentCodeForFollowUp(fu).isEmpty());
        // and a plaintext that is not a code at all
        jdbc.update("update patient_access_links set code_enc = ? where follow_up_id = ?",
                crypto.encryptWithContext("TREM68541212", "access-link:" + p), fu);
        assertTrue(links.currentCodeForFollowUp(fu).isEmpty());
    }

    @Test
    void aLinkCodeCannotBeMovedToAnotherPatient() throws Exception {
        long p = newPatient();
        long q = newPatient();
        JsonNode f = newFollowUp(p, "VISCERAL_SURGERY", "2026-10-13", 7);
        long fu = f.get("followUp").get("id").asLong();
        jdbc.update("update patient_access_links set code_enc = ? where follow_up_id = ?",
                crypto.encryptWithContext(f.get("accessCode").asText(), "access-link:" + q), fu);
        assertTrue(links.currentCodeForFollowUp(fu).isEmpty());
    }
}
