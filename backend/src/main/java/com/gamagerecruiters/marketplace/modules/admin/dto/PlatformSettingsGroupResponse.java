package com.gamagerecruiters.marketplace.modules.admin.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.gamagerecruiters.marketplace.modules.admin.domain.PlatformSetting;

public record PlatformSettingsGroupResponse(
        String group,
        Long version,
        String updatedBy,
        Instant updatedAt,
        Map<String, Object> values,
        List<PlatformSettingDto> settings
) {
    public record PlatformSettingDto(
            String key,
            Object value,
            String valueType,
            String group,
            String updatedBy,
            Instant updatedAt,
            Long version
    ) {
        public static PlatformSettingDto from(PlatformSetting setting) {
            return new PlatformSettingDto(
                    setting.getKey(),
                    setting.getValue(),
                    setting.getValueType(),
                    setting.getGroup(),
                    setting.getUpdatedBy(),
                    setting.getUpdatedAt(),
                    setting.getVersion()
            );
        }
    }
}
