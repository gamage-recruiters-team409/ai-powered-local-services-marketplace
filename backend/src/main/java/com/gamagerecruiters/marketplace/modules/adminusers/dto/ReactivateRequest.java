package com.gamagerecruiters.marketplace.modules.adminusers.dto;

/** Body of POST /{id}/reactivate. {@code note}: required, 10 to 500 characters after trimming. */
public record ReactivateRequest(String note) {
}
