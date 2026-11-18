package com.gamagerecruiters.marketplace.modules.customer.controller;

import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.util.ApiPaths;
import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;
import com.gamagerecruiters.marketplace.modules.customer.domain.FavouriteType;
import com.gamagerecruiters.marketplace.modules.customer.dto.CustomerFavouritePageResponse;
import com.gamagerecruiters.marketplace.modules.customer.service.CustomerFavouriteService;

@RestController
@RequestMapping(ApiPaths.API_V1 + "/customers/me/favorites")
@PreAuthorize("hasRole('CUSTOMER')")
public class CustomerFavouriteController {

    private final CustomerFavouriteService customerFavouriteService;

    public CustomerFavouriteController(CustomerFavouriteService customerFavouriteService) {
        this.customerFavouriteService = customerFavouriteService;
    }

    @GetMapping
    public ResponseEntity<CustomerFavouritePageResponse> getFavourites(
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "6") int size
    ) {
        String currentUserId = CurrentUser.getUserId();
        FavouriteType parsedType = parseType(type);
        CustomerFavouritePageResponse response = customerFavouriteService.getFavourites(currentUserId, parsedType, page, size);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{type}/{targetId}")
    public ResponseEntity<Void> addFavourite(
            @PathVariable String type,
            @PathVariable String targetId
    ) {
        String currentUserId = CurrentUser.getUserId();
        FavouriteType parsedType = parseType(type);
        customerFavouriteService.addFavourite(currentUserId, parsedType, targetId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{type}/{targetId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> removeFavourite(
            @PathVariable String type,
            @PathVariable String targetId
    ) {
        String currentUserId = CurrentUser.getUserId();
        FavouriteType parsedType = parseType(type);
        customerFavouriteService.removeFavourite(currentUserId, parsedType, targetId);
        return ResponseEntity.noContent().build();
    }

    private FavouriteType parseType(String typeStr) {
        if (typeStr == null || typeStr.isBlank()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_FAVOURITE_TYPE",
                    "Favourite type is required and must be PROVIDER or LISTING"
            );
        }
        try {
            return FavouriteType.valueOf(typeStr.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_FAVOURITE_TYPE",
                    "Invalid favourite type: '" + typeStr + "'. Expected PROVIDER or LISTING"
            );
        }
    }
}
