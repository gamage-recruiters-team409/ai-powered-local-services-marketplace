package com.gamagerecruiters.marketplace.modules.auth.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MongoDBContainer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.dto.ChangePasswordRequest;
import com.gamagerecruiters.marketplace.modules.auth.dto.LoginRequest;
import com.gamagerecruiters.marketplace.modules.auth.dto.RefreshTokenRequest;
import com.gamagerecruiters.marketplace.modules.auth.repository.AuthTokenRepository;
import com.gamagerecruiters.marketplace.modules.auth.repository.UserRepository;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;
import com.gamagerecruiters.marketplace.modules.auth.service.UserInternalService;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
@Tag("integration")
@ExtendWith(DockerRequiredCondition.class)
public class AdminPasswordChangeFlowIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(AdminPasswordChangeFlowIntegrationTest.class);

    static MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:7.0");

    @DynamicPropertySource
    static void configureMongoUri(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> {
            if (!mongoDBContainer.isRunning()) {
                mongoDBContainer.start();
            }
            return mongoDBContainer.getReplicaSetUrl();
        });
    }

    @BeforeAll
    static void startContainer() {
        if (!mongoDBContainer.isRunning()) {
            mongoDBContainer.start();
        }
    }

    @AfterAll
    static void stopContainer() {
        if (mongoDBContainer.isRunning()) {
            mongoDBContainer.stop();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserInternalService userInternalService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuthTokenRepository authTokenRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void cleanDatabase() {
        SecurityContextHolder.clearContext();
        mongoTemplate.remove(new Query(), User.class);
        authTokenRepository.deleteAll();
    }

    @Test
    @DisplayName("End-to-End Admin Password Change Lifecycle: seed -> login -> blocked -> change -> revoke -> new login")
    void fullAdminPasswordChangeLifecycleFlow() throws Exception {
        // --- 1. Super Admin creates an admin account through the real service method createAdminAccount ---
        String superAdminId = "super-admin-seed";
        User superAdmin = new User();
        superAdmin.setId(superAdminId);
        superAdmin.setName("Super Admin");
        superAdmin.setEmail("superadmin@marketplace.local");
        superAdmin.setRole(Role.SUPER_ADMIN);
        superAdmin.setStatus(UserStatus.ACTIVE);
        superAdmin.setEmailVerified(true);
        superAdmin.setCreatedAt(Instant.now());
        userRepository.save(superAdmin);

        UserPrincipal superPrincipal = new UserPrincipal(superAdminId, "superadmin@marketplace.local", Role.SUPER_ADMIN);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                superPrincipal, null, List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))
        ));

        String adminEmail = "newadmin@marketplace.local";
        String initialPassword = "InitialAdminPassword123";
        User createdAdmin = userInternalService.createAdminAccount("Operations Admin", adminEmail, initialPassword, Role.ADMIN);

        assertNotNull(createdAdmin);
        assertTrue(createdAdmin.isMustChangePassword(), "Created admin must have mustChangePassword = true");
        log.info("RESULT: Step 1 (createAdminAccount) -> mustChangePassword={}", createdAdmin.isMustChangePassword());

        SecurityContextHolder.clearContext();

        // --- 2. Login of that admin returns 200 with mustChangePassword = true ---
        LoginRequest loginRequest = new LoginRequest(adminEmail, initialPassword);
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(true))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn();

        JsonNode loginJson = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        String initialAccessToken = loginJson.get("accessToken").asText();
        String initialRefreshToken = loginJson.get("refreshToken").asText();
        boolean initialMustChange = loginJson.get("mustChangePassword").asBoolean();
        assertTrue(initialMustChange);
        log.info("RESULT: Step 2 (login) -> status=200, mustChangePassword={}", initialMustChange);

        // --- 3. A normal protected endpoint returns 403 error PASSWORD_CHANGE_REQUIRED ---
        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + initialAccessToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("PASSWORD_CHANGE_REQUIRED"))
                .andExpect(jsonPath("$.message").value("Password change required before accessing this resource"));
        log.info("RESULT: Step 3 (protected endpoint) -> status=403, error=PASSWORD_CHANGE_REQUIRED");

        // --- 4. POST /api/v1/auth/change-password: reject wrong & same, then succeed with 204 ---
        // 4a: Wrong current password
        ChangePasswordRequest wrongOldPassRequest = new ChangePasswordRequest("WrongPassword123", "NewAdminSecurePass456");
        mockMvc.perform(post("/api/v1/auth/change-password")
                        .header("Authorization", "Bearer " + initialAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(wrongOldPassRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("INVALID_CURRENT_PASSWORD"));
        log.info("RESULT: Step 4a (wrong current password) -> status=400, error=INVALID_CURRENT_PASSWORD");

        // 4b: Unchanged password (same as current)
        ChangePasswordRequest samePassRequest = new ChangePasswordRequest(initialPassword, initialPassword);
        mockMvc.perform(post("/api/v1/auth/change-password")
                        .header("Authorization", "Bearer " + initialAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(samePassRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("SAME_PASSWORD"));
        log.info("RESULT: Step 4b (same password) -> status=400, error=SAME_PASSWORD");

        // 4c: Successful password change
        String newPassword = "NewAdminSecurePass456!";
        ChangePasswordRequest validChangeRequest = new ChangePasswordRequest(initialPassword, newPassword);
        mockMvc.perform(post("/api/v1/auth/change-password")
                        .header("Authorization", "Bearer " + initialAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validChangeRequest)))
                .andExpect(status().isNoContent());
        log.info("RESULT: Step 4c (change password success) -> status=204");

        // --- 5. The old refresh token no longer works (token revocation) and login with OLD password fails ---
        RefreshTokenRequest refreshWithOldToken = new RefreshTokenRequest(initialRefreshToken);
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshWithOldToken)))
                .andExpect(status().isUnauthorized());

        LoginRequest oldPasswordLogin = new LoginRequest(adminEmail, initialPassword);
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(oldPasswordLogin)))
                .andExpect(status().isUnauthorized());
        log.info("RESULT: Step 5 (old token revoked & old password login fails) -> refresh=401, old login=401");

        // --- 6. Login with NEW password succeeds with mustChangePassword = false and protected endpoint works ---
        LoginRequest newPasswordLogin = new LoginRequest(adminEmail, newPassword);
        MvcResult newLoginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(newPasswordLogin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(false))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn();

        JsonNode newLoginJson = objectMapper.readTree(newLoginResult.getResponse().getContentAsString());
        String newAccessToken = newLoginJson.get("accessToken").asText();
        boolean newMustChange = newLoginJson.get("mustChangePassword").asBoolean();
        assertFalse(newMustChange, "mustChangePassword must be false after successful change");

        // Normal protected endpoint now succeeds with 200
        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + newAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(adminEmail))
                .andExpect(jsonPath("$.role").value("ADMIN"));
        log.info("RESULT: Step 6 (new password login & protected endpoint success) -> login status=200, mustChangePassword=false, protected endpoint status=200");
    }

    private static void assertNotNull(Object obj) {
        assertThat(obj).isNotNull();
    }
}
