package com.gamagerecruiters.marketplace.modules.admin.repository;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.gamagerecruiters.marketplace.modules.admin.domain.AdminAuditLog;

/**
 * Used only by AdminAuditLogService, and only to insert records. Records are never updated (except the
 * one-time outcome update in the service) or deleted, and no endpoint exposes either operation.
 */
public interface AdminAuditLogRepository extends MongoRepository<AdminAuditLog, String> {
}
