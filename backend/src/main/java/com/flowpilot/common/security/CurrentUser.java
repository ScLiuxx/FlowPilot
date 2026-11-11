package com.flowpilot.common.security;

import com.flowpilot.auth.security.FlowPilotUserDetails;
import com.flowpilot.common.exception.BizException;
import com.flowpilot.common.result.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Helper to read the authenticated user from the security context.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static Long id() {
        FlowPilotUserDetails principal = principal();
        return principal == null ? null : principal.getUserId();
    }

    public static Long requireId() {
        Long id = id();
        if (id == null) {
            throw new BizException(ErrorCode.COMMON_UNAUTHORIZED);
        }
        return id;
    }

    public static String role() {
        FlowPilotUserDetails principal = principal();
        return principal == null ? "USER" : principal.getRole();
    }

    public static boolean isAdmin() {
        return "ADMIN".equals(role());
    }

    private static FlowPilotUserDetails principal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof FlowPilotUserDetails details)) {
            return null;
        }
        return details;
    }
}
