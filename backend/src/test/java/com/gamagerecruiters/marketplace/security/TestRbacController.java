package com.gamagerecruiters.marketplace.security;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;
import com.gamagerecruiters.marketplace.modules.auth.security.OwnershipChecker;

@RestController
@RequestMapping("/api/test")
public class TestRbacController {

    @GetMapping("/customer-only")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<Map<String, String>> customerOnly() {
        return ResponseEntity.ok(Map.of("message", "Hello Customer"));
    }

    @GetMapping("/provider-only")
    @PreAuthorize("hasRole('PROVIDER')")
    public ResponseEntity<Map<String, String>> providerOnly() {
        return ResponseEntity.ok(Map.of("message", "Hello Provider"));
    }

    @GetMapping("/admin-only")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> adminOnly() {
        return ResponseEntity.ok(Map.of("message", "Hello Admin"));
    }

    @GetMapping("/any-admin")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<Map<String, String>> anyAdmin() {
        return ResponseEntity.ok(Map.of("message", "Hello Admin/SuperAdmin"));
    }

    @GetMapping("/ownership/{ownerId}")
    public ResponseEntity<Map<String, String>> checkOwnership(@PathVariable String ownerId) {
        String currentUserId = CurrentUser.getUserId();
        OwnershipChecker.assertOwner(currentUserId, ownerId);
        return ResponseEntity.ok(Map.of("message", "Ownership verified"));
    }
}
