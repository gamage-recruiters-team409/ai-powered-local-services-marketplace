package com.gamagerecruiters.marketplace.modules.notification.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.service.UserInternalService;
import com.gamagerecruiters.marketplace.modules.notification.domain.Notification;
import com.gamagerecruiters.marketplace.modules.notification.domain.NotificationType;
import com.gamagerecruiters.marketplace.modules.notification.repository.NotificationRepository;

class NotificationServiceImplTest {
    private final UserInternalService users = mock(UserInternalService.class);
    private final NotificationRepository repository = mock(NotificationRepository.class);
    private NotificationServiceImpl service(java.util.concurrent.Executor executor) { return new NotificationServiceImpl(users, repository, new NotificationDataSanitizer(), executor); }
    private void active(String id) { when(users.getContactById(id)).thenReturn(new UserInternalService.UserContact(id, "Name", "email", UserStatus.ACTIVE)); }
    @AfterEach void clearSynchronization() { if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization(); }
    @Test void validEventSavesUnreadNotification() { active("user"); service(Runnable::run).notify(new NotificationEvent(NotificationType.BOOKING_CONFIRMED,"user","booking",Map.of())); ArgumentCaptor<Notification> saved=ArgumentCaptor.forClass(Notification.class); verify(repository).save(saved.capture()); org.junit.jupiter.api.Assertions.assertNull(saved.getValue().getReadAt()); }
    @Test void invalidEventsSaveNothingAndNeverThrow() { NotificationServiceImpl s=service(Runnable::run); assertDoesNotThrow(() -> { s.notify(null); s.notify(new NotificationEvent(null,"u",null,null)); s.notify(new NotificationEvent(NotificationType.NEW_MESSAGE," ",null,null)); }); verifyNoInteractions(repository); }
    @Test void inactiveAndUnknownRecipientsAreSkipped() { when(users.getContactById("inactive")).thenReturn(new UserInternalService.UserContact("inactive","N","e",UserStatus.SUSPENDED)); doThrow(new RuntimeException()).when(users).getContactById("unknown"); NotificationServiceImpl s=service(Runnable::run); s.notify(new NotificationEvent(NotificationType.NEW_MESSAGE,"inactive",null,null)); s.notify(new NotificationEvent(NotificationType.NEW_MESSAGE,"unknown",null,null)); verifyNoInteractions(repository); }
    @Test void repositoryFailureIsSwallowed() { active("user"); doThrow(new RuntimeException()).when(repository).save(any()); assertDoesNotThrow(() -> service(Runnable::run).notify(new NotificationEvent(NotificationType.NEW_MESSAGE,"user",null,null))); }
    @Test void activeSynchronizationSavesOnlyAfterCommitAndNeverOnRollback() { active("user"); NotificationServiceImpl s=service(Runnable::run); TransactionSynchronizationManager.initSynchronization(); s.notify(new NotificationEvent(NotificationType.NEW_MESSAGE,"user",null,null)); verifyNoInteractions(repository); TransactionSynchronizationUtils.triggerAfterCommit(); verify(repository).save(any()); TransactionSynchronizationManager.clearSynchronization(); reset(repository); TransactionSynchronizationManager.initSynchronization(); s.notify(new NotificationEvent(NotificationType.NEW_MESSAGE,"user",null,null)); TransactionSynchronizationManager.clearSynchronization(); verifyNoInteractions(repository); }
    @Test void rejectingExecutorAndUnsafeDataNeverEscape() { active("user"); assertDoesNotThrow(() -> service(task -> { throw new RejectedExecutionException(); }).notify(new NotificationEvent(NotificationType.NEW_MESSAGE,"user",null,Map.of("token","x")))); }
    @Test void sanitizesDataBeforeSaving() { active("user"); service(Runnable::run).notify(new NotificationEvent(NotificationType.NEW_MESSAGE,"user",null,Map.of("district"," Kandy ","token","x"))); ArgumentCaptor<Notification> saved=ArgumentCaptor.forClass(Notification.class); verify(repository).save(saved.capture()); org.junit.jupiter.api.Assertions.assertEquals(Map.of("district","Kandy"),saved.getValue().getData()); }
}
