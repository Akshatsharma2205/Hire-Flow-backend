package com.kartgen.hireflow.common;

import java.util.UUID;

/**
 * Holds per-request tenant context via ThreadLocal.
 * Populated by security filters, cleared after request.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> ORG_ID = new ThreadLocal<>();
    private static final ThreadLocal<UUID> USER_ID = new ThreadLocal<>();

    private TenantContext() {}

    public static UUID getOrganizationId() { return ORG_ID.get(); }
    public static void setOrganizationId(UUID orgId) { ORG_ID.set(orgId); }

    public static UUID getUserId() { return USER_ID.get(); }
    public static void setUserId(UUID userId) { USER_ID.set(userId); }

    public static void clear() {
        ORG_ID.remove();
        USER_ID.remove();
    }
}

