package com.aftercare.aftercareapi;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import com.aftercare.aftercareapi.support.IntegrationTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AftercareApiApplicationTests extends IntegrationTest {

	@Test
	void contextLoads() {
	}

	@Test
	void corsPreflightFromFrontendIsAllowed() throws Exception {
		mockMvc.perform(options("/api/patients")
						.header(HttpHeaders.ORIGIN, "http://localhost:4200")
						.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
						.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization, Content-Type"))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:4200"));
	}

	@Test
	void corsPreflightFromUnknownOriginIsRejected() throws Exception {
		mockMvc.perform(options("/api/patients")
						.header(HttpHeaders.ORIGIN, "https://evil.example.com")
						.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
				.andExpect(status().isForbidden());
	}

}
