package com.gamagerecruiters.marketplace.modules.customer.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gamagerecruiters.marketplace.common.util.ApiPaths;
import com.gamagerecruiters.marketplace.modules.customer.dto.DistrictsResponse;
import com.gamagerecruiters.marketplace.modules.customer.service.DistrictCatalog;

@RestController
@RequestMapping(ApiPaths.API_V1 + "/locations/districts")
public class DistrictController {

    private final DistrictCatalog districtCatalog;

    public DistrictController(DistrictCatalog districtCatalog) {
        this.districtCatalog = districtCatalog;
    }

    @GetMapping
    public ResponseEntity<DistrictsResponse> getDistricts() {
        return ResponseEntity.ok(new DistrictsResponse(districtCatalog.getProvinces()));
    }
}
