package com.gamagerecruiters.marketplace.modules.completion.mapper;

import java.util.List;

import com.gamagerecruiters.marketplace.modules.completion.domain.ServiceCompletion;
import com.gamagerecruiters.marketplace.modules.completion.dto.ServiceCompletionResponse;

public final class ServiceCompletionMapper {

    private ServiceCompletionMapper() {
    }

    public static ServiceCompletionResponse toResponse(ServiceCompletion completion) {
        if (completion == null) {
            return null;
        }
        return new ServiceCompletionResponse(
                completion.getId(),
                completion.getBookingId(),
                completion.getStartedAt(),
                completion.getCompletionNote(),
                completion.getPhotoUrls() == null ? List.of() : List.copyOf(completion.getPhotoUrls()),
                completion.getMarkedCompletedAt(),
                completion.getConfirmedAt(),
                completion.getConfirmationType(),
                completion.getIssueRaisedAt(),
                completion.getDisputeId()
        );
    }
}
