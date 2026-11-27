package com.gamagerecruiters.marketplace.modules.admin.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import org.mockito.Mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.admin.domain.AdminAuditAction;
import com.gamagerecruiters.marketplace.modules.admin.domain.AdminAuditLog;
import com.gamagerecruiters.marketplace.modules.admin.domain.AuditOutcome;
import com.gamagerecruiters.marketplace.modules.admin.domain.PlatformSetting;
import com.gamagerecruiters.marketplace.modules.admin.dto.PlatformSettingsGroupResponse;
import com.gamagerecruiters.marketplace.modules.admin.dto.UpdatePlatformSettingsRequest;
import com.gamagerecruiters.marketplace.modules.admin.repository.PlatformSettingRepository;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;

@ExtendWith(MockitoExtension.class)
class PlatformSettingsServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-27T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String ADMIN_ID = "super-admin-1";

    @Mock
    private PlatformSettingRepository repository;

    @Mock
    private AdminAuditLogService auditLogService;

    @Mock
    private MongoTemplate mongoTemplate;

    private PlatformSettingsService service;

    @BeforeEach
    void setUp() {
        service = new PlatformSettingsService(repository, auditLogService, mongoTemplate, CLOCK);
    }

    @Test
    @DisplayName("seedDefaults: seeds missing keys with SRS defaults")
    void seedsMissingDefaults() {
        when(repository.findByKey(anyString())).thenReturn(Optional.empty());

        service.seedDefaults();

        ArgumentCaptor<PlatformSetting> captor = ArgumentCaptor.forClass(PlatformSetting.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        List<PlatformSetting> saved = captor.getAllValues();

        assertThat(saved).anyMatch(s -> "min_booking_notice_hours".equals(s.getKey()) && Integer.valueOf(3).equals(s.getValue()));
        assertThat(saved).anyMatch(s -> "ai_flag_threshold".equals(s.getKey()) && Double.valueOf(0.60).equals(s.getValue()));
        assertThat(saved).anyMatch(s -> "provider_cancellation_refund_pct".equals(s.getKey()) && Integer.valueOf(100).equals(s.getValue()));
    }

    @Test
    @DisplayName("updateGroup: optimistic version conflict throws 409")
    void versionConflictThrows409() {
        List<PlatformSetting> settings = List.of(
                new PlatformSetting("min_booking_notice_hours", 3, "INTEGER", "booking", "sys", NOW, 2L)
        );
        when(repository.findByGroupOrderByKeyAsc("booking")).thenReturn(settings);

        UpdatePlatformSettingsRequest request = new UpdatePlatformSettingsRequest(1L, Map.of("min_booking_notice_hours", 5));

        ApiException ex = catchThrowableOfType(
                () -> service.updateGroup(ADMIN_ID, Role.SUPER_ADMIN, "booking", request),
                ApiException.class
        );

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getErrorCode()).isEqualTo("SETTINGS_VERSION_CONFLICT");
    }

    @Test
    @DisplayName("validateBooking: negative or non-positive value is rejected with 400")
    void bookingValidationRejectsNonPositive() {
        List<PlatformSetting> settings = List.of(
                new PlatformSetting("min_booking_notice_hours", 3, "INTEGER", "booking", "sys", NOW, 1L)
        );
        when(repository.findByGroupOrderByKeyAsc("booking")).thenReturn(settings);

        UpdatePlatformSettingsRequest request = new UpdatePlatformSettingsRequest(1L, Map.of("min_booking_notice_hours", -1));

        ApiException ex = catchThrowableOfType(
                () -> service.updateGroup(ADMIN_ID, Role.SUPER_ADMIN, "booking", request),
                ApiException.class
        );

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getErrorCode()).isEqualTo("INVALID_SETTING_VALUE");
    }

    @Test
    @DisplayName("validateCancellation: provider_cancellation_refund_pct cannot be changed from 100")
    void providerCancellationPctIsLocked() {
        List<PlatformSetting> settings = List.of(
                new PlatformSetting("provider_cancellation_refund_pct", 100, "PERCENTAGE", "cancellation", "sys", NOW, 1L)
        );
        when(repository.findByGroupOrderByKeyAsc("cancellation")).thenReturn(settings);

        UpdatePlatformSettingsRequest request = new UpdatePlatformSettingsRequest(1L, Map.of("provider_cancellation_refund_pct", 80));

        ApiException ex = catchThrowableOfType(
                () -> service.updateGroup(ADMIN_ID, Role.SUPER_ADMIN, "cancellation", request),
                ApiException.class
        );

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getErrorCode()).isEqualTo("LOCKED_SETTING_READONLY");
    }

    @Test
    @DisplayName("validateAiThresholds: flag threshold >= high risk threshold is rejected with 400")
    void aiThresholdFlagMustBeLessThanHighRisk() {
        List<PlatformSetting> settings = List.of(
                new PlatformSetting("ai_flag_threshold", 0.60, "DECIMAL", "ai_thresholds", "sys", NOW, 1L),
                new PlatformSetting("ai_high_risk_threshold", 0.85, "DECIMAL", "ai_thresholds", "sys", NOW, 1L)
        );
        when(repository.findByGroupOrderByKeyAsc("ai_thresholds")).thenReturn(settings);

        UpdatePlatformSettingsRequest request = new UpdatePlatformSettingsRequest(1L, Map.of(
                "ai_flag_threshold", 0.90,
                "ai_high_risk_threshold", 0.80
        ));

        ApiException ex = catchThrowableOfType(
                () -> service.updateGroup(ADMIN_ID, Role.SUPER_ADMIN, "ai_thresholds", request),
                ApiException.class
        );

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getErrorCode()).isEqualTo("INVALID_AI_THRESHOLDS");
    }

    @Test
    @DisplayName("updateGroup: records critical audit entry and increments version")
    void updateGroupRecordsAuditAndSaves() {
        PlatformSetting notice = new PlatformSetting("min_booking_notice_hours", 3, "INTEGER", "booking", "sys", NOW, 1L);
        PlatformSetting buffer = new PlatformSetting("default_buffer_minutes", 30, "INTEGER", "booking", "sys", NOW, 1L);
        List<PlatformSetting> settings = List.of(notice, buffer);
        when(repository.findByGroupOrderByKeyAsc("booking")).thenReturn(settings);

        AdminAuditLog pendingLog = AdminAuditLog.pending("corr-1", ADMIN_ID, Role.SUPER_ADMIN,
                AdminAuditAction.PLATFORM_SETTINGS_UPDATED, "setting:booking", null, null, null, "diff", NOW);
        org.springframework.test.util.ReflectionTestUtils.setField(pendingLog, "id", "audit-123");
        when(auditLogService.recordPending(any())).thenReturn(pendingLog);

        UpdatePlatformSettingsRequest request = new UpdatePlatformSettingsRequest(1L, Map.of("min_booking_notice_hours", 4));

        PlatformSettingsGroupResponse response = service.updateGroup(ADMIN_ID, Role.SUPER_ADMIN, "booking", request);

        ArgumentCaptor<AdminAuditLog> auditCaptor = ArgumentCaptor.forClass(AdminAuditLog.class);
        verify(auditLogService).recordPending(auditCaptor.capture());
        AdminAuditLog capturedAudit = auditCaptor.getValue();
        assertThat(capturedAudit.getAction()).isEqualTo(AdminAuditAction.PLATFORM_SETTINGS_UPDATED);
        assertThat(capturedAudit.getTargetUserId()).isEqualTo("setting:booking");
        assertThat(capturedAudit.getReason()).contains("min_booking_notice_hours: 3 -> 4");

        verify(auditLogService).markSuccess("audit-123");
        verify(repository).saveAll(any());
        assertThat(response.version()).isEqualTo(2L);
    }

    @Test
    @DisplayName("internal typed accessors return typed values or defaults")
    void internalAccessors() {
        when(repository.findByKey("default_buffer_minutes"))
                .thenReturn(Optional.of(new PlatformSetting("default_buffer_minutes", 45, "INTEGER", "booking", "sys", NOW, 1L)));
        when(repository.findByKey("missing_key")).thenReturn(Optional.empty());

        assertThat(service.getInt("default_buffer_minutes", 30)).isEqualTo(45);
        assertThat(service.getInt("missing_key", 30)).isEqualTo(30);
    }
}
