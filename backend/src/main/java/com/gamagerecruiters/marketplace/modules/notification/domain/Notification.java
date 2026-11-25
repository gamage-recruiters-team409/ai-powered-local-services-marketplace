package com.gamagerecruiters.marketplace.modules.notification.domain;

import java.time.Instant;
import java.util.Map;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "notifications")
public class Notification {
    @Id private String id;
    private String userId;
    private NotificationType type;
    private String bookingId;
    private Map<String, String> data;
    private Instant createdAt;
    private Instant readAt;
    private String emailStatus;
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public NotificationType getType() { return type; }
    public void setType(NotificationType type) { this.type = type; }
    public String getBookingId() { return bookingId; }
    public void setBookingId(String bookingId) { this.bookingId = bookingId; }
    public Map<String, String> getData() { return data; }
    public void setData(Map<String, String> data) { this.data = data; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getReadAt() { return readAt; }
    public void setReadAt(Instant readAt) { this.readAt = readAt; }
    public String getEmailStatus() { return emailStatus; }
    public void setEmailStatus(String emailStatus) { this.emailStatus = emailStatus; }
}
