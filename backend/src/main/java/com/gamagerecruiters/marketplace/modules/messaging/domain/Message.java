package com.gamagerecruiters.marketplace.modules.messaging.domain;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "messages")
public class Message {

    @Id
    private String id;
    private String conversationId;
    private String senderId;
    private String body;
    private MessageStatus status;
    private boolean flagged;
    private FlagSource flagSource;
    private Instant flaggedAt;
    private String hideReason;
    private String hiddenBy;
    private Instant hiddenAt;
    private Instant createdAt;
    @Version
    private Long version;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getSenderId() { return senderId; }
    public void setSenderId(String senderId) { this.senderId = senderId; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public MessageStatus getStatus() { return status; }
    public void setStatus(MessageStatus status) { this.status = status; }
    public boolean isFlagged() { return flagged; }
    public void setFlagged(boolean flagged) { this.flagged = flagged; }
    public FlagSource getFlagSource() { return flagSource; }
    public void setFlagSource(FlagSource flagSource) { this.flagSource = flagSource; }
    public Instant getFlaggedAt() { return flaggedAt; }
    public void setFlaggedAt(Instant flaggedAt) { this.flaggedAt = flaggedAt; }
    public String getHideReason() { return hideReason; }
    public void setHideReason(String hideReason) { this.hideReason = hideReason; }
    public String getHiddenBy() { return hiddenBy; }
    public void setHiddenBy(String hiddenBy) { this.hiddenBy = hiddenBy; }
    public Instant getHiddenAt() { return hiddenAt; }
    public void setHiddenAt(Instant hiddenAt) { this.hiddenAt = hiddenAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
