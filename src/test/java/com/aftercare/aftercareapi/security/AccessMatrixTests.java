package com.aftercare.aftercareapi.security;

import com.aftercare.aftercareapi.Enums.AccessRole;
import com.aftercare.aftercareapi.config.security.AccessPolicy;
import com.aftercare.aftercareapi.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/** Every endpoint of the matrix as every role, as an unlinked login, as an inactive member and without a token. */
class AccessMatrixTests extends IntegrationTest {

    static Stream<org.junit.jupiter.params.provider.Arguments> rolesAndRows() {
        return AccessMatrix.ROWS.stream().flatMap(row -> Stream.of(AccessRole.values())
                .map(role -> org.junit.jupiter.params.provider.Arguments.of(row, role)));
    }

    static Stream<AccessMatrix.Row> rows() {
        return AccessMatrix.ROWS.stream();
    }

    private MvcResult perform(AccessMatrix.Row row, org.springframework.test.web.servlet.request.RequestPostProcessor who) throws Exception {
        var b = request(row.method(), row.path()).contentType(MediaType.APPLICATION_JSON).content("{}");
        return mockMvc.perform(who == null ? b : b.with(who)).andReturn();
    }

    @ParameterizedTest(name = "{0} as {1}")
    @MethodSource("rolesAndRows")
    void roleIsAllowedOrDenied(AccessMatrix.Row row, AccessRole role) throws Exception {
        MvcResult r = perform(row, asRole(role));
        int status = r.getResponse().getStatus();
        if (row.allowed().contains(role)) {
            assertNotEquals(401, status, row + " as " + role);
            assertNotEquals(403, status, row + " as " + role + ": " + r.getResponse().getContentAsString());
        } else {
            assertEquals(403, status, row + " as " + role);
            assertEquals("FORBIDDEN", json(r).get("error").asText());
            assertEquals("You do not have permission for this action", json(r).get("message").asText());
        }
    }

    @ParameterizedTest(name = "{0} unlinked or inactive")
    @MethodSource("rows")
    void unlinkedAndInactiveLoginsAreNotStaff(AccessMatrix.Row row) throws Exception {
        for (var who : java.util.List.of(UNLINKED, staffAs(SUB_INACTIVE))) {
            MvcResult r = perform(row, who);
            assertEquals(403, r.getResponse().getStatus(), row.toString());
            assertEquals("NOT_STAFF", json(r).get("error").asText(), row.toString());
            assertEquals("Your login is not linked to an active staff member", json(r).get("message").asText());
        }
    }

    @ParameterizedTest(name = "{0} without a token")
    @MethodSource("rows")
    void noTokenIs401(AccessMatrix.Row row) throws Exception {
        MvcResult r = perform(row, null);
        assertEquals(401, r.getResponse().getStatus(), row.toString());
        assertEquals("", r.getResponse().getContentAsString());
    }

    @Test
    void matrixAndPolicyHaveTheSameRules() {
        assertEquals(AccessMatrix.ROWS.size(), AccessPolicy.RULES.size());
        for (int i = 0; i < AccessMatrix.ROWS.size(); i++) {
            var row = AccessMatrix.ROWS.get(i);
            var rule = AccessPolicy.RULES.get(i);
            assertEquals(row.method(), rule.method(), "rule " + i);
            assertEquals(row.pattern(), rule.pattern(), "rule " + i);
        }
    }

    @Test
    void unlistedApiRoutesAreDeniedEvenForAdmins() throws Exception {
        for (var path : java.util.List.of("/api/not-a-route", "/api/patients/1/secret", "/api/admin/anything")) {
            MvcResult r = mockMvc.perform(request(org.springframework.http.HttpMethod.GET, path).with(asRole(AccessRole.ADMIN))).andReturn();
            assertEquals(403, r.getResponse().getStatus(), path);
            assertEquals("FORBIDDEN", json(r).get("error").asText());
        }
        // a listed path with an unlisted method is denied too
        MvcResult r = mockMvc.perform(request(org.springframework.http.HttpMethod.PATCH, "/api/patients/1").with(asRole(AccessRole.ADMIN))).andReturn();
        assertEquals(403, r.getResponse().getStatus());
    }

    @Test
    void nonApiPathsNeedAnActiveStaffMember() throws Exception {
        assertEquals(403, mockMvc.perform(request(org.springframework.http.HttpMethod.GET, "/v3/api-docs").with(UNLINKED))
                .andReturn().getResponse().getStatus());
        assertNotEquals(403, mockMvc.perform(request(org.springframework.http.HttpMethod.GET, "/v3/api-docs").with(asRole(AccessRole.RECEPTION)))
                .andReturn().getResponse().getStatus());
    }
}
