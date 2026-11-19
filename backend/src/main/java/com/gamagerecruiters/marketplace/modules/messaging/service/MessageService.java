package com.gamagerecruiters.marketplace.modules.messaging.service;

import java.time.Instant;
import com.gamagerecruiters.marketplace.modules.messaging.dto.ConversationPageResponse;
import com.gamagerecruiters.marketplace.modules.messaging.domain.FlagSource;
import com.gamagerecruiters.marketplace.modules.messaging.dto.ModerationMessageResponse;
import com.gamagerecruiters.marketplace.modules.messaging.dto.ModerationMessagePageResponse;
import com.gamagerecruiters.marketplace.modules.messaging.dto.MessagePageResponse;
import com.gamagerecruiters.marketplace.modules.messaging.dto.MessageResponse;

public interface MessageService {

    ConversationPageResponse listConversations(int page, int size);

    MessageResponse sendMessage(String bookingId, String body);

    MessagePageResponse listMessages(String bookingId, int page, int size);

    MessagePageResponse listMessagesSince(String bookingId, Instant since);

    MessagePageResponse getChatHistory(String bookingId, int page, int size);

    ModerationMessagePageResponse getChatHistoryForMediator(String bookingId, int page, int size);

    boolean isParticipant(String conversationId, String userId);

    boolean isMessageParticipant(String messageId, String userId);

    ModerationMessageResponse getMessageForModeration(String messageId);

    /**
     * Caller must first check the reporter with isMessageParticipant.
     */
    void flagMessage(String messageId, FlagSource source);

    ModerationMessageResponse hideMessage(String messageId, String moderatorId, String reason);

    ModerationMessageResponse clearFlag(String messageId);
}
