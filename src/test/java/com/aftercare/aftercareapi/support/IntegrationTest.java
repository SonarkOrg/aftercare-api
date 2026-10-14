package com.aftercare.aftercareapi.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Shared context (fixed clock, mocked JwtDecoder) for all MockMvc integration tests. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
public abstract class IntegrationTest {

    @MockBean
    protected JwtDecoder jwtDecoder;
    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected ObjectMapper om;
    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void resetClock() {
        clock.reset();
    }

    protected static MockHttpServletRequestBuilder staff(MockHttpServletRequestBuilder b) {
        return b.with(jwt().jwt(j -> j.subject("auth0|nurse-a")));
    }

    protected MvcResult call(MockHttpServletRequestBuilder b) throws Exception {
        return mockMvc.perform(staff(b)).andReturn();
    }

    protected JsonNode json(MvcResult r) throws Exception {
        return om.readTree(r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    protected JsonNode postJson(String url, String body) throws Exception {
        MvcResult r = call(post(url).contentType(MediaType.APPLICATION_JSON).content(body));
        return json(r);
    }

    protected long newPatient() throws Exception {
        String body = """
                {"firstName":"Test","lastName":"Patient","email":"t@example.com","phone":"514-555-0100",
                 "gender":"female","address":{"city":"Montreal","state":"QC","zip":"H2X 1Y4"}}""";
        return postJson("/api/patients/register", body).get("id").asLong();
    }

    /** Creates a follow-up (discharged yesterday by default) and returns the FollowUpWithCodeDTO. */
    protected JsonNode newFollowUp(long patientId, String specialty, String dischargeDate, Integer days) throws Exception {
        String body = "{\"specialty\":\"%s\",\"dischargeDate\":\"%s\"%s}".formatted(specialty, dischargeDate,
                days == null ? "" : ",\"durationDays\":" + days);
        MvcResult r = call(post("/api/patients/" + patientId + "/follow-ups").contentType(MediaType.APPLICATION_JSON).content(body));
        if (r.getResponse().getStatus() != 201) {
            throw new AssertionError("create follow-up -> " + r.getResponse().getStatus() + " " + r.getResponse().getContentAsString());
        }
        return json(r);
    }

    protected JsonNode newAppointment(long patientId, String type, String specialty, String scheduledAt) throws Exception {
        String body = "{\"patientId\":%d,\"type\":\"%s\",\"specialty\":\"%s\",\"scheduledAt\":\"%s\",\"durationMinutes\":30}"
                .formatted(patientId, type, specialty, scheduledAt);
        MvcResult r = call(post("/api/appointments").contentType(MediaType.APPLICATION_JSON).content(body));
        if (r.getResponse().getStatus() != 201) {
            throw new AssertionError("create appointment -> " + r.getResponse().getStatus() + " " + r.getResponse().getContentAsString());
        }
        return json(r);
    }

    protected static final String VISCERAL_Q = "https://aftercare.example/fhir/Questionnaire/followup-visceral-surgery|1";

    protected static String checkInBody(int pain, Double temp, boolean redness) {
        return """
                {"resourceType":"QuestionnaireResponse","questionnaire":"%s","status":"completed","item":[
                 {"linkId":"PAIN","answer":[{"valueInteger":%d}]},
                 %s
                 {"linkId":"WOUND_REDNESS","answer":[{"valueBoolean":%s}]},
                 {"linkId":"WOUND_DISCHARGE","answer":[{"valueBoolean":false}]},
                 {"linkId":"NAUSEA_VOMITING","answer":[{"valueBoolean":false}]},
                 {"linkId":"ABLE_TO_EAT","answer":[{"valueBoolean":true}]},
                 {"linkId":"SHORTNESS_OF_BREATH","answer":[{"valueBoolean":false}]}]}"""
                .formatted(VISCERAL_Q, pain, temp == null ? "" : "{\"linkId\":\"TEMPERATURE\",\"answer\":[{\"valueDecimal\":" + temp + "}]},", redness);
    }

    protected static String intakeBody(String qUrl, boolean surgery, boolean anticoagulant, boolean withName) {
        String common = """
                {"linkId":"WILL_ATTEND","answer":[{"valueBoolean":true}]},
                {"linkId":"TAKES_ANTICOAGULANT","answer":[{"valueBoolean":%s}]},
                %s
                {"linkId":"NEEDS_INTERPRETER","answer":[{"valueBoolean":false}]}"""
                .formatted(anticoagulant, withName ? "{\"linkId\":\"ANTICOAGULANT_NAME\",\"answer\":[{\"valueString\":\"Eliquis hier\"}]}," : "");
        String extra = surgery
                ? ",{\"linkId\":\"RECENT_ILLNESS\",\"answer\":[{\"valueBoolean\":false}]},{\"linkId\":\"FASTING_ACKNOWLEDGED\",\"answer\":[{\"valueBoolean\":true}]},"
                  + "{\"linkId\":\"RIDE_HOME\",\"answer\":[{\"valueBoolean\":true}]},{\"linkId\":\"CONSENT_READ\",\"answer\":[{\"valueBoolean\":true}]}"
                : ",{\"linkId\":\"REASON_FOR_VISIT\",\"answer\":[{\"valueString\":\"Douleur\"}]}";
        return "{\"resourceType\":\"QuestionnaireResponse\",\"questionnaire\":\"" + qUrl + "|1\",\"status\":\"completed\",\"item\":["
                + common + extra + "]}";
    }

    protected static final String SURGERY_URL = "https://aftercare.example/fhir/Questionnaire/pre-surgery";
    protected static final String CONSULT_URL = "https://aftercare.example/fhir/Questionnaire/pre-consult";
}
