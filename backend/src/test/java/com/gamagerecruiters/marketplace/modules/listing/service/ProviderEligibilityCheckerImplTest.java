package com.gamagerecruiters.marketplace.modules.listing.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.service.UserInternalService;

@ExtendWith(MockitoExtension.class)
class ProviderEligibilityCheckerImplTest {

    @Mock
    private UserInternalService userInternalService;

    @Mock
    private ObjectProvider<ProviderVerificationPort> verificationPortProvider;

    @Mock
    private ProviderVerificationPort verificationPort;

    private ProviderEligibilityCheckerImpl checker;

    @BeforeEach
    void setUp() {
        checker = new ProviderEligibilityCheckerImpl(userInternalService, verificationPortProvider);
    }

    private User user(Role role, UserStatus status) {
        User u = new User();
        u.setId("u-1");
        u.setRole(role);
        u.setStatus(status);
        return u;
    }

    @Test
    @DisplayName("Rejects null/blank userId or non-PROVIDER role with AccessDeniedException")
    void rejectsMissingOrNonProvider() {
        assertThatThrownBy(() -> checker.assertEligibleToManageListing(null, true))
                .isInstanceOf(AccessDeniedException.class);

        when(userInternalService.getById("cust-1")).thenReturn(user(Role.CUSTOMER, UserStatus.ACTIVE));
        assertThatThrownBy(() -> checker.assertEligibleToManageListing("cust-1", false))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("Rejects suspended or inactive provider accounts with 403 PROVIDER_ACCOUNT_INACTIVE")
    void rejectsInactiveProviderAccount() {
        when(userInternalService.getById("prov-1")).thenReturn(user(Role.PROVIDER, UserStatus.SUSPENDED));

        assertThatThrownBy(() -> checker.assertEligibleToManageListing("prov-1", false))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThatCode(() -> {
                    if (!"PROVIDER_ACCOUNT_INACTIVE".equals(((ApiException) ex).getErrorCode())) {
                        throw new AssertionError("Unexpected code: " + ((ApiException) ex).getErrorCode());
                    }
                }).doesNotThrowAnyException());
    }

    @Test
    @DisplayName("Allows ACTIVE provider when WP02 verification port is not yet registered, and enforces APPROVED when registered")
    void checksOptionalVerificationPortOnPublish() {
        when(userInternalService.getById("prov-1")).thenReturn(user(Role.PROVIDER, UserStatus.ACTIVE));
        when(verificationPortProvider.getIfAvailable()).thenReturn(null);

        assertThatCode(() -> checker.assertEligibleToManageListing("prov-1", true)).doesNotThrowAnyException();

        when(verificationPortProvider.getIfAvailable()).thenReturn(verificationPort);
        when(verificationPort.isProviderVerified("prov-1")).thenReturn(false);

        assertThatCode(() -> checker.assertEligibleToManageListing("prov-1", false)).doesNotThrowAnyException();
        assertThatThrownBy(() -> checker.assertEligibleToManageListing("prov-1", true))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("APPROVED");
    }
}
