package com.gamagerecruiters.marketplace.modules.customer.dto;

import java.time.Instant;

import com.gamagerecruiters.marketplace.modules.customer.domain.CustomerAddress;

public record CustomerAddressResponse(
        String id,
        String customerId,
        String label,
        String addressLine1,
        String addressLine2,
        String city,
        String district,
        String postalCode,
        boolean isDefault,
        Instant createdAt,
        Instant updatedAt
) {
    public static CustomerAddressResponse fromEntity(CustomerAddress entity) {
        return new CustomerAddressResponse(
                entity.getId(),
                entity.getCustomerId(),
                entity.getLabel(),
                entity.getAddressLine1(),
                entity.getAddressLine2(),
                entity.getCity(),
                entity.getDistrict(),
                entity.getPostalCode(),
                entity.isDefault(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
