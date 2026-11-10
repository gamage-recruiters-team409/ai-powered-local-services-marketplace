package com.gamagerecruiters.marketplace.modules.customer.dto;

import java.util.List;

public record ProvinceDto(
        String name,
        List<String> districts
) {
}
