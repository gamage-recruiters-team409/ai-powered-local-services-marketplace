package com.gamagerecruiters.marketplace;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.gamagerecruiters.marketplace.modules.admin.repository.AdminAuditLogRepository;
import com.gamagerecruiters.marketplace.modules.auth.repository.AuthTokenRepository;
import com.gamagerecruiters.marketplace.modules.auth.repository.UserRepository;
import com.gamagerecruiters.marketplace.modules.auth.security.JwtAuthenticationFilter;
import com.gamagerecruiters.marketplace.modules.auth.security.RateLimitExceededException;
import com.gamagerecruiters.marketplace.modules.auth.security.RateLimitService;
import com.gamagerecruiters.marketplace.modules.customer.repository.CustomerAddressRepository;
import com.gamagerecruiters.marketplace.modules.customer.service.BookingOpenBookingsChecker;
import com.gamagerecruiters.marketplace.modules.availability.repository.AvailabilityRuleRepository;
import com.gamagerecruiters.marketplace.modules.availability.repository.BlockedDateRepository;
import com.gamagerecruiters.marketplace.modules.catalog.repository.CategoryRepository;
import com.gamagerecruiters.marketplace.modules.booking.repository.BookingRepository;
import com.gamagerecruiters.marketplace.modules.completion.repository.ServiceCompletionRepository;
import com.gamagerecruiters.marketplace.modules.dispute.repository.DisputeRepository;
import com.gamagerecruiters.marketplace.modules.payment.repository.PaymentRepository;
import com.gamagerecruiters.marketplace.modules.provider.repository.ProviderProfileRepository;
import com.gamagerecruiters.marketplace.modules.review.repository.ReviewRepository;

@SpringBootTest
@ActiveProfiles("test")
class ApplicationContextRealStartupTest {

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private AuthTokenRepository authTokenRepository;

    @MockitoBean
    private AvailabilityRuleRepository availabilityRuleRepository;

    @MockitoBean
    private BlockedDateRepository blockedDateRepository;

    @MockitoBean
    private DisputeRepository disputeRepository;

    @MockitoBean
    private CategoryRepository categoryRepository;

    @MockitoBean
    private BookingRepository bookingRepository;

    @MockitoBean
    private CustomerAddressRepository customerAddressRepository;

    @MockitoBean
    private ProviderProfileRepository providerProfileRepository;

    @MockitoBean
    private AdminAuditLogRepository adminAuditLogRepository;

    @MockitoBean
    private ServiceCompletionRepository serviceCompletionRepository;

    @MockitoBean
    private ReviewRepository reviewRepository;

    @MockitoBean
    private PaymentRepository paymentRepository;

    @MockitoBean
    private MongoTemplate mongoTemplate;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    @DisplayName("Verify real Spring context loads without circular dependencies and key security beans exist")
    void contextLoadsAndKeySecurityBeansExist() {
                
        assertNotNull(applicationContext.getBean(PasswordEncoder.class), "PasswordEncoder bean must exist");
        assertNotNull(applicationContext.getBean(JwtAuthenticationFilter.class), "JwtAuthenticationFilter bean must exist");
        assertNotNull(applicationContext.getBean(SecurityFilterChain.class), "SecurityFilterChain bean must exist");
        assertNotNull(applicationContext.getBean(RateLimitService.class), "RateLimitService bean must exist");
        assertNotNull(applicationContext.getBean(BookingOpenBookingsChecker.class), "BookingOpenBookingsChecker bean must exist");
    }

    @Test
    @DisplayName("RateLimitService bean uses real default properties (5 requests per 15 minutes for sensitive)")
    void rateLimitServiceUsesDefaultSensitiveProperties() {
        RateLimitService rateLimiter = applicationContext.getBean(RateLimitService.class);
        assertNotNull(rateLimiter, "RateLimitService bean must exist");

        String testUserId = "real-props-sensitive-user";
        for (int i = 0; i < 5; i++) {
            assertDoesNotThrow(() -> rateLimiter.checkSensitive(testUserId));
        }

        RateLimitExceededException ex = assertThrows(
                RateLimitExceededException.class,
                () -> rateLimiter.checkSensitive(testUserId)
        );
        assertEquals("RATE_LIMITED", ex.getErrorCode());
    }
}