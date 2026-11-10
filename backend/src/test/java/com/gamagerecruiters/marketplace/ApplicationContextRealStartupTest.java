package com.gamagerecruiters.marketplace;

import static org.junit.jupiter.api.Assertions.assertNotNull;

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

import com.gamagerecruiters.marketplace.modules.auth.repository.AuthTokenRepository;
import com.gamagerecruiters.marketplace.modules.auth.repository.UserRepository;
import com.gamagerecruiters.marketplace.modules.auth.security.JwtAuthenticationFilter;
import com.gamagerecruiters.marketplace.modules.availability.repository.AvailabilityRuleRepository;
import com.gamagerecruiters.marketplace.modules.availability.repository.BlockedDateRepository;
import com.gamagerecruiters.marketplace.modules.booking.repository.BookingRepository;

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
    private BookingRepository bookingRepository;

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
    }
}
