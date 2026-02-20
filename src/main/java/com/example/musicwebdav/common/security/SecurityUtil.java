package com.example.musicwebdav.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public class SecurityUtil {

    public static Long getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal) {
            return ((UserPrincipal) authentication.getPrincipal()).getId();
        }
        // Fallback for non-authenticated requests or background tasks if needed
        // For strict isolation, throw exception or return null
        return 1L; // Default admin user for now during migration
    }
}
