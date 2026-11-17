package com.gamagerecruiters.marketplace.modules.verification.controller;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.gamagerecruiters.marketplace.common.util.ApiPaths;
import com.gamagerecruiters.marketplace.modules.verification.domain.VerificationDocumentType;
import com.gamagerecruiters.marketplace.modules.verification.dto.VerificationDocumentResponse;
import com.gamagerecruiters.marketplace.modules.verification.service.VerificationDocumentService;

@RestController
@RequestMapping(
        ApiPaths.API_V1 + "/providers/me/documents"
)
@PreAuthorize("hasRole('PROVIDER')")
public class VerificationDocumentController {

    private final VerificationDocumentService verificationDocumentService;

    public VerificationDocumentController(
            VerificationDocumentService verificationDocumentService
    ) {
        this.verificationDocumentService =
                verificationDocumentService;
    }

    @PostMapping(
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<VerificationDocumentResponse>
    uploadDocument(
            @RequestParam("documentType")
            VerificationDocumentType documentType,

            @RequestParam(
                    value = "issueDate",
                    required = false
            )
            String issueDate,

            @RequestParam(
                    value = "expiryDate",
                    required = false
            )
            String expiryDate,

            @RequestParam("file")
            MultipartFile file
    ) {

        LocalDate parsedIssueDate =
                parseOptionalDate(
                        issueDate,
                        "issueDate"
                );

        LocalDate parsedExpiryDate =
                parseOptionalDate(
                        expiryDate,
                        "expiryDate"
                );

        VerificationDocumentResponse response =
                verificationDocumentService
                        .uploadMyDocument(
                                documentType,
                                parsedIssueDate,
                                parsedExpiryDate,
                                file
                        );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @GetMapping
    public ResponseEntity<
            List<VerificationDocumentResponse>
            > getDocuments() {

        return ResponseEntity.ok(
                verificationDocumentService
                        .getMyDocuments()
        );
    }

    private LocalDate parseOptionalDate(
            String value,
            String fieldName
    ) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return LocalDate.parse(value);

        } catch (DateTimeParseException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    fieldName
                            + " must use YYYY-MM-DD format"
            );
        }
    }
}