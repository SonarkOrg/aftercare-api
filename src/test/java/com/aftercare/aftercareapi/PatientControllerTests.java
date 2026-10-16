package com.aftercare.aftercareapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import com.aftercare.aftercareapi.support.IntegrationTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PatientControllerTests extends IntegrationTest {

	private ObjectMapper objectMapper;

	@org.junit.jupiter.api.BeforeEach
	void init() {
		objectMapper = om;
	}

	private static String patientJson(String firstName, String email) {
		return """
				{
				  "id": 9999,
				  "firstName": "%s",
				  "lastName": "Tremblay",
				  "email": "%s",
				  "phone": "514-555-0100",
				  "gender": "female",
				  "password": "should-be-ignored",
				  "address": {"city": "Montreal", "state": "QC", "zip": "H2X 1Y4"}
				}
				""".formatted(firstName, email);
	}

	private long createPatient() throws Exception {
		String body = mockMvc.perform(post("/api/patients/register")
						.with(staffAs(SUB_ADMIN))
						.contentType(MediaType.APPLICATION_JSON)
						.content(patientJson("Alice", "alice@example.com")))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		JsonNode node = objectMapper.readTree(body);
		return node.get("id").asLong();
	}

	@Test
	void unauthenticatedListIsRejected() throws Exception {
		mockMvc.perform(get("/api/patients"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void createReturnsDtoWithIdAndNoPassword() throws Exception {
		mockMvc.perform(post("/api/patients/register")
						.with(staffAs(SUB_ADMIN))
						.contentType(MediaType.APPLICATION_JSON)
						.content(patientJson("Alice", "alice@example.com")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").isNumber())
				.andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.not(9999)))
				.andExpect(jsonPath("$.firstName").value("Alice"))
				.andExpect(jsonPath("$.password").doesNotExist());
	}

	@Test
	void getByIdReturnsPatient() throws Exception {
		long id = createPatient();
		mockMvc.perform(get("/api/patients/" + id).with(staffAs(SUB_ADMIN)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id))
				.andExpect(jsonPath("$.address.city").value("Montreal"))
				.andExpect(jsonPath("$.password").doesNotExist());
	}

	@Test
	void listReturnsOk() throws Exception {
		createPatient();
		mockMvc.perform(get("/api/patients").with(staffAs(SUB_ADMIN)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isArray());
	}

	@Test
	void getMissingReturnsNotFound() throws Exception {
		mockMvc.perform(get("/api/patients/123456789").with(staffAs(SUB_ADMIN)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error").value("NOT_FOUND"))
				.andExpect(jsonPath("$.message").value("Patient not found"))
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.fieldErrors").isMap());
	}

	@Test
	void summaryHasFollowUpAndAlertCount() throws Exception {
		long plain = createPatient();
		long withFollowUp = createPatient();
		JsonNode f = newFollowUp(withFollowUp, "VISCERAL_SURGERY", "2026-10-13", null);
		mockMvc.perform(post("/api/public/q/" + f.get("accessCode").asText()).contentType(MediaType.APPLICATION_JSON)
				.content(checkInBody(8, null, false))).andExpect(status().isCreated());
		JsonNode list = objectMapper.readTree(mockMvc.perform(get("/api/patients").with(staffAs(SUB_ADMIN)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		JsonNode a = null;
		JsonNode b = null;
		long prev = 0;
		for (JsonNode n : list) {
			org.junit.jupiter.api.Assertions.assertTrue(n.get("id").asLong() > prev);
			prev = n.get("id").asLong();
			if (n.get("id").asLong() == plain) a = n;
			if (n.get("id").asLong() == withFollowUp) b = n;
		}
		org.junit.jupiter.api.Assertions.assertTrue(a.get("activeFollowUp").isNull());
		org.junit.jupiter.api.Assertions.assertEquals(0, a.get("unresolvedAlertCount").asInt());
		org.junit.jupiter.api.Assertions.assertEquals("ACTIVE", b.get("activeFollowUp").get("status").asText());
		org.junit.jupiter.api.Assertions.assertEquals(1, b.get("unresolvedAlertCount").asInt());
		org.junit.jupiter.api.Assertions.assertEquals("Alice", b.get("firstName").asText());
	}

	@Test
	void deleteWithFollowUpOrAppointmentConflicts() throws Exception {
		long p1 = createPatient();
		newFollowUp(p1, "VISCERAL_SURGERY", "2026-10-13", null);
		mockMvc.perform(delete("/api/patients/" + p1).with(staffAs(SUB_ADMIN)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error").value("PATIENT_HAS_RECORDS"));
		long p2 = createPatient();
		newAppointment(p2, "GENERAL_CONSULT", "VISCERAL_SURGERY", "2026-10-16T08:00:00");
		mockMvc.perform(delete("/api/patients/" + p2).with(staffAs(SUB_ADMIN)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error").value("PATIENT_HAS_RECORDS"));
	}

	@Test
	void deleteWithOnlyANoteConflicts() throws Exception {
		long id = createPatient();
		mockMvc.perform(post("/api/patients/" + id + "/notes").with(staffAs(SUB_ADMIN)).contentType(MediaType.APPLICATION_JSON)
						.content("{\"body\":\"hello\"}"))
				.andExpect(status().isCreated());
		mockMvc.perform(delete("/api/patients/" + id).with(staffAs(SUB_ADMIN)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error").value("PATIENT_HAS_RECORDS"));
	}

	@Test
	void deleteThenGetReturnsNotFound() throws Exception {
		long id = createPatient();
		mockMvc.perform(delete("/api/patients/" + id).with(staffAs(SUB_ADMIN)))
				.andExpect(status().isNoContent());
		mockMvc.perform(get("/api/patients/" + id).with(staffAs(SUB_ADMIN)))
				.andExpect(status().isNotFound());
	}

	@Test
	void deleteMissingReturnsNotFound() throws Exception {
		mockMvc.perform(delete("/api/patients/123456789").with(staffAs(SUB_ADMIN)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error").value("NOT_FOUND"));
	}

	@Test
	void blankFirstNameReturnsBadRequest() throws Exception {
		mockMvc.perform(post("/api/patients/register")
						.with(staffAs(SUB_ADMIN))
						.contentType(MediaType.APPLICATION_JSON)
						.content(patientJson("", "alice@example.com")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.fieldErrors.firstName").exists());
	}

	@Test
	void invalidEmailReturnsBadRequest() throws Exception {
		mockMvc.perform(post("/api/patients/register")
						.with(staffAs(SUB_ADMIN))
						.contentType(MediaType.APPLICATION_JSON)
						.content(patientJson("Alice", "not-an-email")))
				.andExpect(status().isBadRequest());
	}

	@Test
	void overlongFirstNameReturnsBadRequest() throws Exception {
		mockMvc.perform(post("/api/patients/register")
						.with(staffAs(SUB_ADMIN))
						.contentType(MediaType.APPLICATION_JSON)
						.content(patientJson("a".repeat(300), "alice@example.com")))
				.andExpect(status().isBadRequest());
	}

	@Test
	void missingAddressReturnsBadRequest() throws Exception {
		String body = """
				{"firstName": "Alice", "lastName": "Tremblay", "email": "alice@example.com",
				 "phone": "514-555-0100", "gender": "female"}
				""";
		mockMvc.perform(post("/api/patients/register")
						.with(staffAs(SUB_ADMIN))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isBadRequest());
	}

	@Test
	void unauthenticatedPostIsRejected() throws Exception {
		mockMvc.perform(post("/api/patients/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content(patientJson("Alice", "alice@example.com")))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void unauthenticatedDeleteIsRejected() throws Exception {
		mockMvc.perform(delete("/api/patients/1"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void unauthenticatedGetByIdIsRejected() throws Exception {
		mockMvc.perform(get("/api/patients/1"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void invalidBearerTokenIsRejected() throws Exception {
		when(jwtDecoder.decode(anyString())).thenThrow(new BadJwtException("bad token"));
		mockMvc.perform(get("/api/patients").header("Authorization", "Bearer garbage"))
				.andExpect(status().isUnauthorized());
	}

}
