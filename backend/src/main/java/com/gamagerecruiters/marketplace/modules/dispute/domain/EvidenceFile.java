package com.gamagerecruiters.marketplace.modules.dispute.domain;

import java.time.Instant;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;

/**
 * Metadata of one uploaded evidence file, embedded in the dispute. The file itself is a private (authenticated)
 * asset in evidence storage; it is only ever opened through a short-lived signed URL.
 *
 * <ul>
 *   <li>{@code id}: random id used in the API (never the storage id).</li>
 *   <li>{@code publicId}/{@code format}: storage reference. Internal: never returned by the API.</li>
 *   <li>{@code fileName}: the original name, sanitised (shown to people only; never used as a storage name).</li>
 *   <li>{@code type}: the detected type (from the file content, not the extension).</li>
 * </ul>
 */
public class EvidenceFile {

    private String id;
    private String publicId;
    private String format;
    private String fileName;
    private EvidenceType type;
    private long sizeBytes;
    private Role uploadedByRole;
    private Instant uploadedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPublicId() {
        return publicId;
    }

    public void setPublicId(String publicId) {
        this.publicId = publicId;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public EvidenceType getType() {
        return type;
    }

    public void setType(EvidenceType type) {
        this.type = type;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public Role getUploadedByRole() {
        return uploadedByRole;
    }

    public void setUploadedByRole(Role uploadedByRole) {
        this.uploadedByRole = uploadedByRole;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }

    public void setUploadedAt(Instant uploadedAt) {
        this.uploadedAt = uploadedAt;
    }
}
