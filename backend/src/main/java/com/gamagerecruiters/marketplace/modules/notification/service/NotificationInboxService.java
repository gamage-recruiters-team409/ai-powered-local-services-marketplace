package com.gamagerecruiters.marketplace.modules.notification.service;
import com.gamagerecruiters.marketplace.modules.notification.dto.NotificationPageResponse;
import com.gamagerecruiters.marketplace.modules.notification.dto.NotificationResponse;
public interface NotificationInboxService {
    NotificationPageResponse listMine(boolean unreadOnly, int page, int size);
    NotificationResponse markRead(String id);
    long markAllRead();
    long unreadCount();
}
