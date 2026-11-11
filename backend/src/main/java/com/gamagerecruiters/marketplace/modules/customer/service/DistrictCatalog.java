package com.gamagerecruiters.marketplace.modules.customer.service;

import java.util.List;
import java.util.Set;

import com.gamagerecruiters.marketplace.modules.customer.dto.ProvinceDto;

public interface DistrictCatalog {

    List<ProvinceDto> getProvinces();

    Set<String> getAllDistricts();

    boolean isValidDistrict(String district);
}
