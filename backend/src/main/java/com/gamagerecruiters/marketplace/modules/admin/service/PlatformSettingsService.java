package com.gamagerecruiters.marketplace.modules.admin.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.admin.domain.AdminAuditAction;
import com.gamagerecruiters.marketplace.modules.admin.domain.AdminAuditLog;
import com.gamagerecruiters.marketplace.modules.admin.domain.PlatformSetting;
import com.gamagerecruiters.marketplace.modules.admin.dto.PlatformSettingAuditHistoryResponse;
import com.gamagerecruiters.marketplace.modules.admin.dto.PlatformSettingsGroupResponse;
import com.gamagerecruiters.marketplace.modules.admin.dto.PlatformSettingsGroupResponse.PlatformSettingDto;
import com.gamagerecruiters.marketplace.modules.admin.dto.UpdatePlatformSettingsRequest;
import com.gamagerecruiters.marketplace.modules.admin.repository.PlatformSettingRepository;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;

/**
 * Service managing platform canonical settings (FR-ADM-008).
 * SUPER_ADMIN only for mutations. Seeded on startup with SRS defaults.
 *
 * <p><b>Important honesty:</b> storing a value here does not change any other module's runtime
 * behavior. The screen manages the canonical configuration; enforcement by each owning module
 * is separate and out of scope.</p>
 */
@Service
public class PlatformSettingsService {

    private static final Logger log = LoggerFactory.getLogger(PlatformSettingsService.class);

    public static final String GROUP_BOOKING = "booking";
    public static final String GROUP_CANCELLATION = "cancellation";
    public static final String GROUP_CUSTOMER = "customer";
    public static final String GROUP_DISPUTE = "dispute";
    public static final String GROUP_PENALTY = "penalty";
    public static final String GROUP_AI_THRESHOLDS = "ai_thresholds";

    public static final Set<String> ALLOWED_GROUPS = Set.of(
            GROUP_BOOKING,
            GROUP_CANCELLATION,
            GROUP_CUSTOMER,
            GROUP_DISPUTE,
            GROUP_PENALTY,
            GROUP_AI_THRESHOLDS
    );

    private final PlatformSettingRepository repository;
    private final AdminAuditLogService auditLogService;
    private final MongoTemplate mongoTemplate;
    private final Clock clock;

    @Autowired
    public PlatformSettingsService(PlatformSettingRepository repository,
                                   AdminAuditLogService auditLogService,
                                   MongoTemplate mongoTemplate) {
        this(repository, auditLogService, mongoTemplate, Clock.systemUTC());
    }

    public PlatformSettingsService(PlatformSettingRepository repository,
                                   AdminAuditLogService auditLogService,
                                   MongoTemplate mongoTemplate,
                                   Clock clock) {
        this.repository = repository;
        this.auditLogService = auditLogService;
        this.mongoTemplate = mongoTemplate;
        this.clock = clock;
    }

    /**
     * Seeds default configuration keys on startup if missing.
     */
    public void seedDefaults() {
        Instant now = clock.instant();
        List<PlatformSetting> defaults = List.of(
                // Booking
                new PlatformSetting("min_booking_notice_hours", 3, "INTEGER", GROUP_BOOKING, "system", now, 1L),
                new PlatformSetting("provider_response_cutoff_hours", 1, "INTEGER", GROUP_BOOKING, "system", now, 1L),
                new PlatformSetting("default_buffer_minutes", 30, "INTEGER", GROUP_BOOKING, "system", now, 1L),
                new PlatformSetting("max_reschedules_per_booking", 2, "INTEGER", GROUP_BOOKING, "system", now, 1L),
                new PlatformSetting("max_series_occurrences", 8, "INTEGER", GROUP_BOOKING, "system", now, 1L),
                new PlatformSetting("max_series_length_months", 3, "INTEGER", GROUP_BOOKING, "system", now, 1L),
                new PlatformSetting("provider_confirmation_window_hours", 12, "INTEGER", GROUP_BOOKING, "system", now, 1L),

                // Cancellation
                new PlatformSetting("refund_full_pct", 100, "PERCENTAGE", GROUP_CANCELLATION, "system", now, 1L),
                new PlatformSetting("refund_partial_pct", 50, "PERCENTAGE", GROUP_CANCELLATION, "system", now, 1L),
                new PlatformSetting("refund_none_pct", 0, "PERCENTAGE", GROUP_CANCELLATION, "system", now, 1L),
                new PlatformSetting("provider_cancellation_refund_pct", 100, "PERCENTAGE", GROUP_CANCELLATION, "system", now, 1L),
                new PlatformSetting("repeat_cancellation_threshold", 3, "INTEGER", GROUP_CANCELLATION, "system", now, 1L),

                // Customer
                new PlatformSetting("auto_confirm_completion_hours", 48, "INTEGER", GROUP_CUSTOMER, "system", now, 1L),
                new PlatformSetting("review_window_days", 14, "INTEGER", GROUP_CUSTOMER, "system", now, 1L),

                // Dispute
                new PlatformSetting("dispute_raise_window_hours", 72, "INTEGER", GROUP_DISPUTE, "system", now, 1L),
                new PlatformSetting("no_show_report_delay_hours", 2, "INTEGER", GROUP_DISPUTE, "system", now, 1L),
                new PlatformSetting("dispute_response_period_hours", 72, "INTEGER", GROUP_DISPUTE, "system", now, 1L),
                new PlatformSetting("dispute_appeal_window_hours", 48, "INTEGER", GROUP_DISPUTE, "system", now, 1L),

                // Penalty
                new PlatformSetting("provider_penalty_pct", 30, "PERCENTAGE", GROUP_PENALTY, "system", now, 1L),

                // AI thresholds
                new PlatformSetting("ai_flag_threshold", 0.60, "DECIMAL", GROUP_AI_THRESHOLDS, "system", now, 1L),
                new PlatformSetting("ai_high_risk_threshold", 0.85, "DECIMAL", GROUP_AI_THRESHOLDS, "system", now, 1L)
        );

        int seeded = 0;
        for (PlatformSetting def : defaults) {
            if (repository.findByKey(def.getKey()).isEmpty()) {
                repository.save(def);
                seeded++;
            }
        }
        if (seeded > 0) {
            log.info("event=PLATFORM_SETTINGS_SEEDED seededKeysCount={}", seeded);
        }
    }

    public Map<String, PlatformSettingsGroupResponse> getAllGrouped() {
        Map<String, PlatformSettingsGroupResponse> result = new LinkedHashMap<>();
        for (String group : List.of(GROUP_BOOKING, GROUP_CANCELLATION, GROUP_CUSTOMER, GROUP_DISPUTE, GROUP_PENALTY, GROUP_AI_THRESHOLDS)) {
            result.put(group, getGroup(group));
        }
        return result;
    }

    public PlatformSettingsGroupResponse getGroup(String group) {
        String normalized = normalizeGroup(group);
        List<PlatformSetting> settings = repository.findByGroupOrderByKeyAsc(normalized);
        if (settings.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "SETTINGS_GROUP_NOT_FOUND", "No settings found for group " + group);
        }

        long maxVersion = settings.stream().mapToLong(PlatformSetting::getVersion).max().orElse(1L);
        Instant latestUpdated = settings.stream()
                .map(PlatformSetting::getUpdatedAt)
                .filter(t -> t != null)
                .max(Instant::compareTo)
                .orElse(clock.instant());
        String lastUpdatedBy = settings.stream()
                .filter(s -> s.getUpdatedAt() != null && s.getUpdatedAt().equals(latestUpdated))
                .map(PlatformSetting::getUpdatedBy)
                .findFirst()
                .orElse("system");

        Map<String, Object> values = new LinkedHashMap<>();
        List<PlatformSettingDto> dtos = new ArrayList<>();
        for (PlatformSetting s : settings) {
            values.put(s.getKey(), s.getValue());
            dtos.add(PlatformSettingDto.from(s));
        }

        return new PlatformSettingsGroupResponse(normalized, maxVersion, lastUpdatedBy, latestUpdated, values, dtos);
    }

    public PlatformSettingsGroupResponse updateGroup(String actorId, Role actorRole, String group, UpdatePlatformSettingsRequest request) {
        String normalized = normalizeGroup(group);
        List<PlatformSetting> currentSettings = repository.findByGroupOrderByKeyAsc(normalized);
        if (currentSettings.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "SETTINGS_GROUP_NOT_FOUND", "No settings found for group " + group);
        }

        long currentVersion = currentSettings.stream().mapToLong(PlatformSetting::getVersion).max().orElse(1L);
        if (request.version() == null || !request.version().equals(currentVersion)) {
            throw new ApiException(HttpStatus.CONFLICT, "SETTINGS_VERSION_CONFLICT",
                    "Settings have been modified by another administrator (version " + currentVersion + "). Please reload latest settings.");
        }

        Map<String, Object> newValues = request.values();
        if (newValues == null || newValues.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SETTINGS_EMPTY_VALUES", "No settings values provided");
        }

        validateGroupValues(normalized, currentSettings, newValues);

        StringBuilder diff = new StringBuilder("[").append(normalized).append("] ");
        boolean hasChanges = false;
        long nextVersion = currentVersion + 1;
        Instant now = clock.instant();

        List<PlatformSetting> toSave = new ArrayList<>();
        for (PlatformSetting setting : currentSettings) {
            if (newValues.containsKey(setting.getKey())) {
                Object newVal = newValues.get(setting.getKey());
                Object currentVal = setting.getValue();
                if (!valuesEqual(currentVal, newVal)) {
                    if (hasChanges) diff.append("; ");
                    diff.append(setting.getKey()).append(": ").append(currentVal).append(" -> ").append(newVal);
                    setting.setValue(newVal);
                    setting.setUpdatedBy(actorId);
                    setting.setUpdatedAt(now);
                    setting.setVersion(nextVersion);
                    toSave.add(setting);
                    hasChanges = true;
                }
            }
        }

        if (!hasChanges) {
            return getGroup(normalized);
        }

        // ONE critical audit entry per save capturing before->after
        String correlationId = UUID.randomUUID().toString();
        AdminAuditLog pending = AdminAuditLog.pending(
                correlationId,
                actorId,
                actorRole,
                AdminAuditAction.PLATFORM_SETTINGS_UPDATED,
                "setting:" + normalized,
                null,
                null,
                null,
                diff.toString(),
                now
        );

        AdminAuditLog recordedAudit = auditLogService.recordPending(pending);

        try {
            repository.saveAll(toSave);
            auditLogService.markSuccess(recordedAudit.getId());
            log.info("event=PLATFORM_SETTINGS_SAVED group={} actorId={} version={}", normalized, actorId, nextVersion);
        } catch (RuntimeException ex) {
            auditLogService.markFailed(recordedAudit.getId(), ex.getClass().getSimpleName());
            throw ex;
        }

        return getGroup(normalized);
    }

    public List<PlatformSettingAuditHistoryResponse> getHistory(String group) {
        String normalized = normalizeGroup(group);
        Query query = Query.query(Criteria.where("action").is(AdminAuditAction.PLATFORM_SETTINGS_UPDATED)
                .and("targetUserId").is("setting:" + normalized))
                .with(Sort.by(Sort.Direction.DESC, "createdAt"))
                .limit(20);

        List<AdminAuditLog> logs = mongoTemplate.find(query, AdminAuditLog.class);
        return logs.stream()
                .map(l -> new PlatformSettingAuditHistoryResponse(
                        l.getId(),
                        l.getActorId(),
                        l.getCreatedAt(),
                        l.getReason()
                ))
                .toList();
    }

    private void validateGroupValues(String group, List<PlatformSetting> current, Map<String, Object> values) {
        switch (group) {
            case GROUP_BOOKING -> validateBooking(values);
            case GROUP_CANCELLATION -> validateCancellation(values);
            case GROUP_CUSTOMER -> validateCustomer(values);
            case GROUP_DISPUTE -> validateDispute(values);
            case GROUP_PENALTY -> validatePenalty(values);
            case GROUP_AI_THRESHOLDS -> validateAiThresholds(current, values);
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SETTINGS_GROUP", "Unknown group: " + group);
        }
    }

    private void validateBooking(Map<String, Object> values) {
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String key = entry.getKey();
            int val = requireInt(key, entry.getValue());
            if ("max_reschedules_per_booking".equals(key)) {
                if (val < 0) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SETTING_VALUE", key + " must be non-negative");
                }
            } else {
                if (val <= 0) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SETTING_VALUE", key + " must be a positive integer");
                }
            }
        }
    }

    private void validateCancellation(Map<String, Object> values) {
        if (values.containsKey("provider_cancellation_refund_pct")) {
            int providerPct = requireInt("provider_cancellation_refund_pct", values.get("provider_cancellation_refund_pct"));
            if (providerPct != 100) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "LOCKED_SETTING_READONLY",
                        "provider_cancellation_refund_pct is locked and cannot be changed from 100%");
            }
        }
        for (String pctKey : List.of("refund_full_pct", "refund_partial_pct", "refund_none_pct")) {
            if (values.containsKey(pctKey)) {
                int val = requireInt(pctKey, values.get(pctKey));
                if (val < 0 || val > 100) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SETTING_VALUE", pctKey + " must be between 0 and 100");
                }
            }
        }
        if (values.containsKey("repeat_cancellation_threshold")) {
            int threshold = requireInt("repeat_cancellation_threshold", values.get("repeat_cancellation_threshold"));
            if (threshold <= 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SETTING_VALUE", "repeat_cancellation_threshold must be positive");
            }
        }
    }

    private void validateCustomer(Map<String, Object> values) {
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            int val = requireInt(entry.getKey(), entry.getValue());
            if (val <= 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SETTING_VALUE", entry.getKey() + " must be positive");
            }
        }
    }

    private void validateDispute(Map<String, Object> values) {
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            int val = requireInt(entry.getKey(), entry.getValue());
            if (val <= 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SETTING_VALUE", entry.getKey() + " must be positive");
            }
        }
    }

    private void validatePenalty(Map<String, Object> values) {
        if (values.containsKey("provider_penalty_pct")) {
            int val = requireInt("provider_penalty_pct", values.get("provider_penalty_pct"));
            if (val < 0 || val > 100) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SETTING_VALUE", "provider_penalty_pct must be between 0 and 100");
            }
        }
    }

    private void validateAiThresholds(List<PlatformSetting> current, Map<String, Object> values) {
        double flag = values.containsKey("ai_flag_threshold")
                ? requireDouble("ai_flag_threshold", values.get("ai_flag_threshold"))
                : getSettingFromList(current, "ai_flag_threshold", 0.60);

        double highRisk = values.containsKey("ai_high_risk_threshold")
                ? requireDouble("ai_high_risk_threshold", values.get("ai_high_risk_threshold"))
                : getSettingFromList(current, "ai_high_risk_threshold", 0.85);

        if (flag < 0.0 || flag > 1.0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SETTING_VALUE", "ai_flag_threshold must be between 0.00 and 1.00");
        }
        if (highRisk < 0.0 || highRisk > 1.0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SETTING_VALUE", "ai_high_risk_threshold must be between 0.00 and 1.00");
        }
        if (flag >= highRisk) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AI_THRESHOLDS",
                    "ai_flag_threshold (" + flag + ") must be strictly less than ai_high_risk_threshold (" + highRisk + ")");
        }
    }

    private static double getSettingFromList(List<PlatformSetting> list, String key, double defaultVal) {
        return list.stream()
                .filter(s -> s.getKey().equals(key))
                .findFirst()
                .map(s -> {
                    if (s.getValue() instanceof Number n) return n.doubleValue();
                    return Double.parseDouble(String.valueOf(s.getValue()));
                })
                .orElse(defaultVal);
    }

    private static int requireInt(String key, Object obj) {
        if (obj == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SETTING_VALUE", key + " cannot be null");
        }
        if (obj instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(obj).trim());
        } catch (NumberFormatException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SETTING_VALUE", key + " must be a valid integer");
        }
    }

    private static double requireDouble(String key, Object obj) {
        if (obj == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SETTING_VALUE", key + " cannot be null");
        }
        if (obj instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(obj).trim());
        } catch (NumberFormatException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SETTING_VALUE", key + " must be a valid number");
        }
    }

    private static boolean valuesEqual(Object a, Object b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        if (a instanceof Number na && b instanceof Number nb) {
            return Double.compare(na.doubleValue(), nb.doubleValue()) == 0;
        }
        return String.valueOf(a).equals(String.valueOf(b));
    }

    private static String normalizeGroup(String group) {
        if (group == null || group.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SETTINGS_GROUP_REQUIRED", "Group is required");
        }
        String normalized = group.strip().toLowerCase(Locale.ROOT);
        if (!ALLOWED_GROUPS.contains(normalized)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "SETTINGS_GROUP_NOT_FOUND", "Unknown settings group: " + group);
        }
        return normalized;
    }

    // ---------------------------------------------------------------- internal read accessors

    public int getInt(String key, int defaultValue) {
        return repository.findByKey(key)
                .map(PlatformSetting::getValue)
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .map(Number::intValue)
                .orElse(defaultValue);
    }

    public double getDouble(String key, double defaultValue) {
        return repository.findByKey(key)
                .map(PlatformSetting::getValue)
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .map(Number::doubleValue)
                .orElse(defaultValue);
    }

    public <T> Optional<T> getSetting(String key, Class<T> targetType) {
        return repository.findByKey(key)
                .map(PlatformSetting::getValue)
                .filter(targetType::isInstance)
                .map(targetType::cast);
    }
}
