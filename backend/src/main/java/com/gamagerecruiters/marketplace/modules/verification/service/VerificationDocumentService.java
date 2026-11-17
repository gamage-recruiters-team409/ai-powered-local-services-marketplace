package com.gamagerecruiters.marketplace.modules.verification.service;

import java.time.LocalDate;
import java.util.List;

import org.springframework.web.multipart.MultipartFile;

import com.gamagerecruiters.marketplace.modules.verification.domain.VerificationDocumentType;
import com.gamagerecruiters.marketplace.modules.verification.dto.VerificationDocumentResponse;

public interface VerificationDocumentService {

    VerificationDocumentResponse uploadMyDocument(
            VerificationDocumentType documentType,
            LocalDate issueDate,
            LocalDate expiryDate,
            MultipartFile file
    );

    List<VerificationDocumentResponse> getMyDocuments();
}