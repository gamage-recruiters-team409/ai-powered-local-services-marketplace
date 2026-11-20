package com.gamagerecruiters.marketplace.modules.search.controller;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gamagerecruiters.marketplace.common.util.ApiPaths;
import com.gamagerecruiters.marketplace.modules.search.dto.SearchRequest;
import com.gamagerecruiters.marketplace.modules.search.dto.SearchResponse;
import com.gamagerecruiters.marketplace.modules.search.service.SearchService;

@RestController
@RequestMapping(ApiPaths.API_V1 + "/search")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping
    public SearchResponse search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false, defaultValue = "false") boolean verified,
            @RequestParam(required = false, defaultValue = "recommended") String sort,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "12") int size,
            @RequestParam MultiValueMap<String, String> allParams
    ) {
        Set<String> presentParams = allParams == null
                ? Set.of()
                : new LinkedHashSet<>(allParams.keySet());
        return searchService.search(new SearchRequest(
                q,
                location,
                category,
                maxPrice,
                verified,
                sort,
                page,
                size,
                presentParams
        ));
    }
}
