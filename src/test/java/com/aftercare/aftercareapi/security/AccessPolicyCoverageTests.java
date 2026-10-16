package com.aftercare.aftercareapi.security;

import com.aftercare.aftercareapi.config.security.AccessPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** A new controller route cannot be left open: every /api mapping must be matched by a rule of the policy table. */
@SpringBootTest
class AccessPolicyCoverageTests {

    @MockBean
    JwtDecoder jwtDecoder;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @Test
    void everyApiMappingIsCoveredByARule() {
        Set<AccessPolicy.Rule> used = new HashSet<>();
        int checked = 0;
        for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
            Set<String> patterns = info.getPathPatternsCondition().getPatternValues();
            for (String pattern : patterns) {
                if (!pattern.startsWith("/api/") || pattern.startsWith("/api/public/")) {
                    continue;
                }
                assertFalse(info.getMethodsCondition().getMethods().isEmpty(), "mapping without a method: " + pattern);
                for (var m : info.getMethodsCondition().getMethods()) {
                    String path = pattern.replaceAll("\\{[^}]*}", "1");
                    MockHttpServletRequest req = new MockHttpServletRequest(m.name(), path);
                    req.setServletPath(path);
                    var rule = AccessPolicy.firstMatch(req);
                    assertTrue(rule.isPresent(), "no access rule for " + m + " " + pattern);
                    assertEquals(HttpMethod.valueOf(m.name()), rule.get().method(), pattern);
                    used.add(rule.get());
                    checked++;
                }
            }
        }
        assertTrue(checked >= 80, "only " + checked + " mappings found");
        // and no stale rule: every rule guards at least one real mapping
        for (AccessPolicy.Rule rule : AccessPolicy.RULES) {
            assertTrue(used.contains(rule), "rule without a handler: " + rule);
        }
    }

    @Test
    void policyAndMatrixHaveTheSameSize() {
        assertEquals(AccessMatrix.ROWS.size(), AccessPolicy.RULES.size());
    }
}
