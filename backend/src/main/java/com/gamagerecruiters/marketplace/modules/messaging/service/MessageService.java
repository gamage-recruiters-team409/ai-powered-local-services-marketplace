package com.gamagerecruiters.marketplace.modules.messaging.service;

import com.gamagerecruiters.marketplace.modules.messaging.dto.MessagePageResponse;
import com.gamagerecruiters.marketplace.modules.messaging.dto.MessageResponse;

public interface MessageService {

    MessageResponse sendMessage(String bookingId, String body);

    MessagePageResponse listMessages(String bookingId, int page, int size);
}
