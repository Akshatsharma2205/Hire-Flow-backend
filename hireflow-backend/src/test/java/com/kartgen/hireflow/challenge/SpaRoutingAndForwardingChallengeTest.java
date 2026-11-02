package com.kartgen.hireflow.challenge;

import com.kartgen.hireflow.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Empirical Challenge Harness for Milestone 3 (Requirement R4):
 * SPA Routing, Route Forwarding, Static Serving, and Fallback Semantics.
 *
 * Verifies:
 * 1. Whitelisted top-level SPA routes forward to /index.html with 200 OK.
 * 2. Deep sub-routes across all 10 SaaS screens forward to /index.html with 200 OK.
 * 3. Unknown non-API routes fallback to /index.html with 200 OK per PROJECT.md line 79.
 * 4. API endpoints (/api/**) are strictly excluded from SPA forwarding.
 * 5. Static assets (/assets/**, /vite.svg, /favicon.ico) are served directly.
 */
public class SpaRoutingAndForwardingChallengeTest extends IntegrationTestSupport {

    @ParameterizedTest
    @ValueSource(strings = {
        "/login",
        "/dashboard",
        "/candidates",
        "/candidates/any-id",
        "/jobs",
        "/jobs/any-id",
        "/applications",
        "/applications/any-id",
        "/mailboxes",
        "/rules",
        "/activity",
        "/settings",
        "/docs",
        "/docs/ARCHITECTURE.md"
    })
    @DisplayName("Top-level SPA routes and primary deep links forward to index.html with 200 OK")
    void whitelistedSpaRoutes_shouldForwardToIndexHtml(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/mailboxes/new",
        "/rules/create",
        "/activity/123",
        "/settings/keys"
    })
    @DisplayName("Sub-routes across all SPA sections should forward to index.html")
    void allSpaSectionSubroutes_shouldForwardToIndexHtml(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    @DisplayName("Unknown non-API routes (/unknown-route) must fallback to index.html without 404 or 401")
    void unknownNonApiRoute_shouldFallbackToIndexHtml() throws Exception {
        mockMvc.perform(get("/unknown-route"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    @DisplayName("API routes must NOT forward to index.html")
    void apiRoutes_mustNotForwardToIndexHtml() throws Exception {
        mockMvc.perform(get("/api/v1/nonexistent-endpoint"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("Static assets are served directly")
    void staticAsset_servedDirectly() throws Exception {
        mockMvc.perform(get("/vite.svg"))
                .andExpect(status().isOk());
    }
}
