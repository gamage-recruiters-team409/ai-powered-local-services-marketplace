package com.gamagerecruiters.marketplace.modules.listing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.listing.service.ListingMediaValidator.ValidatedMedia;

class ListingMediaValidatorTest {

    private ListingMediaValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ListingMediaValidator();
    }

    @Test
    @DisplayName("Publish accepts 1..8 valid HTTP(S) images, deduplicates them, and defaults coverImageUrl")
    void publishAcceptsValidImagesAndDefaultsCover() {
        ValidatedMedia result = validator.validate(
                List.of(
                        " https://cdn.example.com/photo1.JPG ",
                        "https://cdn.example.com/photo2.webp?v=2",
                        "https://cdn.example.com/photo1.JPG"
                ),
                null,
                true
        );

        assertThat(result.images()).containsExactly(
                "https://cdn.example.com/photo1.JPG",
                "https://cdn.example.com/photo2.webp?v=2"
        );
        assertThat(result.coverImageUrl()).isEqualTo("https://cdn.example.com/photo1.JPG");
    }

    @Test
    @DisplayName("Draft allows zero images, while publish requires at least one image")
    void draftAllowsEmptyImagesButPublishRequiresOne() {
        ValidatedMedia draft = validator.validate(List.of(), null, false);
        assertThat(draft.images()).isEmpty();
        assertThat(draft.coverImageUrl()).isNull();

        assertThatThrownBy(() -> validator.validate(List.of(), null, true))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(apiEx.getErrorCode()).isEqualTo("LISTING_IMAGES_REQUIRED");
                });
    }

    @Test
    @DisplayName("Rejects more than 8 unique images")
    void rejectsMoreThanEightImages() {
        List<String> nineImages = List.of(
                "https://cdn.example.com/1.jpg",
                "https://cdn.example.com/2.jpg",
                "https://cdn.example.com/3.jpg",
                "https://cdn.example.com/4.jpg",
                "https://cdn.example.com/5.jpg",
                "https://cdn.example.com/6.jpg",
                "https://cdn.example.com/7.jpg",
                "https://cdn.example.com/8.jpg",
                "https://cdn.example.com/9.jpg"
        );

        assertThatThrownBy(() -> validator.validate(nineImages, null, true))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode()).isEqualTo("TOO_MANY_IMAGES"));
    }

    @Test
    @DisplayName("Rejects non-HTTP schemes, unsupported file extensions, and mismatched coverImageUrl")
    void rejectsInvalidUrlsAndCoverMismatch() {
        assertThatThrownBy(() -> validator.validate(List.of("javascript:alert(1).png"), null, true))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode()).isEqualTo("INVALID_MEDIA_URL"));

        assertThatThrownBy(() -> validator.validate(List.of("https://cdn.example.com/payload.svg"), null, true))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode()).isEqualTo("UNSUPPORTED_IMAGE_FORMAT"));

        assertThatThrownBy(() -> validator.validate(
                List.of("https://cdn.example.com/a.png"),
                "https://cdn.example.com/other.png",
                true
        ))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode()).isEqualTo("INVALID_COVER_IMAGE"));
    }
}
