package com.gamagerecruiters.marketplace.modules.admin.dto;

import java.time.Instant;

public record PlatformSettingAuditHistoryResponse(
        String id,
        String actorId,
        Instant createdAt,
        String diff
) {
}
