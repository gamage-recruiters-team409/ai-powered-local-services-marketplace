package com.gamagerecruiters.marketplace.modules.search.service;

import com.gamagerecruiters.marketplace.modules.search.dto.SearchRequest;
import com.gamagerecruiters.marketplace.modules.search.dto.SearchResponse;

public interface SearchService {

    SearchResponse search(SearchRequest request);
}
