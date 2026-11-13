package com.gamagerecruiters.marketplace.modules.auth.security;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gamagerecruiters.marketplace.common.exception.GlobalExceptionHandler;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class AdminRolePolicyStatusCodeTest {

    @Mock
    private UserRepository userRepository;

    private MockMvc mockMvc;

    private User testActor;
    private User testTarget;
    private UserStatus newStatus;

    @RestController
    @RequestMapping("/test/admin-policy")
    class TestPolicyController {
        @PutMapping("/status/{targetId}")
        public ResponseEntity<Void> testStatusChange(@PathVariable String targetId) {
            AdminRolePolicy.validateStatusChange(testActor, testTarget, newStatus, userRepository);
            return ResponseEntity.ok().build();
        }
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new TestPolicyController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private User createUser(String id, Role role, UserStatus status) {
        User user = new User();
        user.setId(id);
        user.setEmail(id + "@marketplace.local");
        user.setRole(role);
        user.setStatus(status);
        return user;
    }

    @Test
    @DisplayName("Case 1: Inactive actor results in 403 Forbidden with error code ACTOR_ACCOUNT_INACTIVE")
    void shouldReturn403WhenActorIsInactive() throws Exception {
        testActor = createUser("actor-1", Role.ADMIN, UserStatus.SUSPENDED);
        testTarget = createUser("target-1", Role.CUSTOMER, UserStatus.ACTIVE);
        newStatus = UserStatus.SUSPENDED;

        mockMvc.perform(put("/test/admin-policy/status/target-1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("ACTOR_ACCOUNT_INACTIVE"))
                .andExpect(jsonPath("$.message").value("Actor account must be active to modify user status"));
    }

    @Test
    @DisplayName("Case 2: Self-modification results in 403 Forbidden with error code CANNOT_MODIFY_SELF")
    void shouldReturn403WhenSelfModificationAttempted() throws Exception {
        testActor = createUser("super-1", Role.SUPER_ADMIN, UserStatus.ACTIVE);
        testTarget = createUser("super-1", Role.SUPER_ADMIN, UserStatus.ACTIVE);
        newStatus = UserStatus.SUSPENDED;

        mockMvc.perform(put("/test/admin-policy/status/super-1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("CANNOT_MODIFY_SELF"))
                .andExpect(jsonPath("$.message").value("Self status modification is not allowed"));
    }

    @Test
    @DisplayName("Case 3: Role hierarchy violation results in 403 Forbidden with error code ROLE_HIERARCHY_VIOLATION")
    void shouldReturn403WhenRoleHierarchyViolated() throws Exception {
        testActor = createUser("admin-1", Role.ADMIN, UserStatus.ACTIVE);
        testTarget = createUser("super-1", Role.SUPER_ADMIN, UserStatus.ACTIVE);
        newStatus = UserStatus.SUSPENDED;

        mockMvc.perform(put("/test/admin-policy/status/super-1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("ROLE_HIERARCHY_VIOLATION"))
                .andExpect(jsonPath("$.message").value("Actor role 'ADMIN' is not permitted to modify account status of target role 'SUPER_ADMIN'"));
    }

    @Test
    @DisplayName("Case 4: Modifying deactivated account results in 403 Forbidden with error code ACCOUNT_DEACTIVATED")
    void shouldReturn403WhenTargetIsDeactivated() throws Exception {
        testActor = createUser("admin-1", Role.ADMIN, UserStatus.ACTIVE);
        testTarget = createUser("cust-1", Role.CUSTOMER, UserStatus.DEACTIVATED);
        newStatus = UserStatus.ACTIVE;

        mockMvc.perform(put("/test/admin-policy/status/cust-1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("ACCOUNT_DEACTIVATED"))
                .andExpect(jsonPath("$.message").value("Cannot modify status of a DEACTIVATED account"));
    }

    @Test
    @DisplayName("Case 5: Modifying last active SUPER_ADMIN results in 409 Conflict with error code LAST_ACTIVE_SUPER_ADMIN")
    void shouldReturn409WhenTargetIsLastActiveSuperAdmin() throws Exception {
        testActor = createUser("super-1", Role.SUPER_ADMIN, UserStatus.ACTIVE);
        testTarget = createUser("super-2", Role.SUPER_ADMIN, UserStatus.ACTIVE);
        newStatus = UserStatus.SUSPENDED;

        when(userRepository.countByRoleAndStatus(Role.SUPER_ADMIN, UserStatus.ACTIVE)).thenReturn(1L);

        mockMvc.perform(put("/test/admin-policy/status/super-2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("LAST_ACTIVE_SUPER_ADMIN"))
                .andExpect(jsonPath("$.message").value("Cannot deactivate, suspend or lock the last remaining ACTIVE SUPER_ADMIN"));
    }
}
