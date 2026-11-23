
package com.gamagerecruiters.marketplace.modules.provider.dto;

import java.time.Instant;

import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfileActivityStatus;

public record ProviderProfileActivityResponse(

        ProviderProfileActivityStatus activityStatus,

        boolean canReceiveNewBookings,

        Instant updatedAt

) {
}
