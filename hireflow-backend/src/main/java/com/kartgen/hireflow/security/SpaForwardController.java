package com.kartgen.hireflow.security;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Forwards client-side Single Page Application (SPA) routes to index.html.
 * API endpoints (/api/**), actuator (/actuator/**), and static assets (/assets/**)
 * are excluded from forwarding so they are dispatched directly.
 */
@Controller
public class SpaForwardController {

    @GetMapping(value = {
        "/login",
        "/dashboard",
        "/candidates",
        "/candidates/**",
        "/jobs",
        "/jobs/**",
        "/applications",
        "/applications/**",
        "/mailboxes",
        "/mailboxes/**",
        "/rules",
        "/rules/**",
        "/activity",
        "/activity/**",
        "/settings",
        "/settings/**",
        "/docs",
        "/docs/**",
        "/{path:[^\\.]*}"
    })
    public String forwardSpaRoutes() {
        return "forward:/index.html";
    }
}
