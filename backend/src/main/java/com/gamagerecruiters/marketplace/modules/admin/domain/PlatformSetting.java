package com.gamagerecruiters.marketplace.modules.admin.domain;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A platform setting stored in the {@code settings} collection (FR-ADM-008).
 * Manages the canonical configuration for policy groups.
 */
@Document(collection = "settings")
public class PlatformSetting {

    @Id
    private String id;

    @Indexed(unique = true)
    private String key;

    private Object value;

    private String valueType; // "INTEGER", "PERCENTAGE", "DECIMAL", "STRING", etc.

    @Indexed
    private String group; // "booking", "cancellation", "customer", "dispute", "penalty", "ai_thresholds"

    private String updatedBy;

    private Instant updatedAt;

    private Long version = 1L;

    public PlatformSetting() {
    }

    public PlatformSetting(String key, Object value, String valueType, String group, String updatedBy, Instant updatedAt, Long version) {
        this.key = key;
        this.value = value;
        this.valueType = valueType;
        this.group = group;
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
        this.version = version == null ? 1L : version;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public Object getValue() {
        return value;
    }

    public void setValue(Object value) {
        this.value = value;
    }

    public String getValueType() {
        return valueType;
    }

    public void setValueType(String valueType) {
        this.valueType = valueType;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
