package com.gamagerecruiters.marketplace.modules.dispute.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.dispute.port.UserDirectoryPort;

/** Input checks of the admin list; nothing reaches the database when the input is invalid. */
@ExtendWith(MockitoExtension.class)
class DisputeAdminQueryServiceTest {

    @Mock
    private MongoTemplate mongoTemplate;
    @Mock
    private UserDirectoryPort userDirectory;

    private Throwable search(String status, String category, String q, LocalDate from, LocalDate to, String sort,
                             int page, int size) {
        DisputeAdminQueryService service = new DisputeAdminQueryService(mongoTemplate, userDirectory);
        return catchThrowable(() -> service.search(status, category, q, from, to, sort, page, size));
    }

    private static void assertBadRequest(Throwable thrown, String code) {
        assertThat(thrown).isInstanceOf(ApiException.class);
        assertThat(((ApiException) thrown).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(((ApiException) thrown).getErrorCode()).isEqualTo(code);
    }

    @ParameterizedTest
    @ValueSource(strings = {"password", "createdAt,sideways", "createdAt,asc,extra", "$where", "description"})
    @DisplayName("sort: only whitelisted fields and asc/desc -> otherwise 400 INVALID_SORT")
    void sortWhitelist(String sort) {
        assertBadRequest(search(null, null, null, null, null, sort, 0, 20), "INVALID_SORT");
        verifyNoInteractions(mongoTemplate);
    }

    @Test
    @DisplayName("sort: default createdAt desc, then _id for a stable order")
    void defaultSort() {
        assertThat(DisputeAdminQueryService.parseSort(null))
                .isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "_id")));
        assertThat(DisputeAdminQueryService.parseSort("status,asc").getOrderFor("status").getDirection())
                .isEqualTo(Sort.Direction.ASC);
    }

    @ParameterizedTest
    @ValueSource(strings = {".*", "a b", "x{1}", "$ne",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    @DisplayName("q: only id-like text (letters, digits, '_', '-', max 64) -> otherwise 400 INVALID_SEARCH_TEXT")
    void searchTextIsSafe(String q) {
        assertBadRequest(search(null, null, q, null, null, null, 0, 20), "INVALID_SEARCH_TEXT");
        verifyNoInteractions(mongoTemplate);
    }

    @Test
    @DisplayName("filters: unknown status/category, from after to, page limits -> 400")
    void otherFilters() {
        assertBadRequest(search("WAITING", null, null, null, null, null, 0, 20), "INVALID_STATUS_FILTER");
        assertBadRequest(search(null, "BANANA", null, null, null, null, 0, 20), "INVALID_CATEGORY_FILTER");
        assertBadRequest(search(null, null, null, LocalDate.parse("2026-11-10"), LocalDate.parse("2026-11-01"),
                null, 0, 20), "INVALID_DATE_RANGE");
        assertBadRequest(search(null, null, null, null, null, null, -1, 20), "INVALID_PAGE_REQUEST");
        assertBadRequest(search(null, null, null, null, null, null, 0, 101), "INVALID_PAGE_REQUEST");
        verifyNoInteractions(mongoTemplate);
    }
}
