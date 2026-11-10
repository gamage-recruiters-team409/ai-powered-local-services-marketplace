package com.gamagerecruiters.marketplace.modules.auth.domain;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "users")
public class User {

    @Id
    private String id;
    private String name;
    private String email;
    private String phone;
    private String passwordHash;
    private Role role;
    private UserStatus status;
    private boolean emailVerified;
    private Boolean consentStatus;
    private Instant consentAt;
    private String consentPolicyVersion;
    private int failedLoginCount;
    private Instant lockStartedAt;
    private Instant createdAt;

    public User() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email != null ? email.toLowerCase().trim() : null;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone != null ? phone.trim() : null;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public UserStatus getStatus() {
        return status;
    }

    public void setStatus(UserStatus status) {
        this.status = status;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    public void setEmailVerified(boolean emailVerified) {
        this.emailVerified = emailVerified;
    }

    public Boolean getConsentStatus() {
        return consentStatus;
    }

    public void setConsentStatus(Boolean consentStatus) {
        this.consentStatus = consentStatus;
    }

    public Instant getConsentAt() {
        return consentAt;
    }

    public void setConsentAt(Instant consentAt) {
        this.consentAt = consentAt;
    }

    public String getConsentPolicyVersion() {
        return consentPolicyVersion;
    }

    public void setConsentPolicyVersion(String consentPolicyVersion) {
        this.consentPolicyVersion = consentPolicyVersion;
    }

    public int getFailedLoginCount() {
        return failedLoginCount;
    }

    public void setFailedLoginCount(int failedLoginCount) {
        this.failedLoginCount = failedLoginCount;
    }

    public Instant getLockStartedAt() {
        return lockStartedAt;
    }

    public void setLockStartedAt(Instant lockStartedAt) {
        this.lockStartedAt = lockStartedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
