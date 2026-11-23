package com.gamagerecruiters.marketplace.modules.notification.service;
import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.gamagerecruiters.marketplace.modules.notification.domain.NotificationType;
class NotificationTextBuilderTest {
    private final NotificationTextBuilder builder = new NotificationTextBuilder();
    @Test void suppliesNonBlankTextForEveryType() { for (NotificationType type : NotificationType.values()) { NotificationText text=builder.build(type,Map.of()); assertFalse(text.title().isBlank()); assertFalse(text.message().isBlank()); } }
    @Test void appendsOnlyApprovedOptionalDetails() { NotificationText text=builder.build(NotificationType.BOOKING_CONFIRMED,Map.of("district","Kandy","slotStart","Monday","reference","R1","reason","Rain","other","hidden")); assertTrue(text.message().contains("Kandy")); assertFalse(text.message().contains("hidden")); }
}
