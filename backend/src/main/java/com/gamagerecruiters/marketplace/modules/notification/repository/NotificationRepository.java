package com.gamagerecruiters.marketplace.modules.notification.repository;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import com.gamagerecruiters.marketplace.modules.notification.domain.Notification;
public interface NotificationRepository extends MongoRepository<Notification, String> {
    Page<Notification> findByUserId(String userId, Pageable pageable);
    Page<Notification> findByUserIdAndReadAtIsNull(String userId, Pageable pageable);
}
