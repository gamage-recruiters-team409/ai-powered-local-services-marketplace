package com.gamagerecruiters.marketplace.modules.completion.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.gamagerecruiters.marketplace.modules.completion.service.ServiceCompletionService;

@ExtendWith(MockitoExtension.class)
class CompletionAutoConfirmJobTest {

    private static final Instant NOW = Instant.parse("2026-11-11T08:00:00Z");

    @Mock
    private ServiceCompletionService completionService;

    @Test
    @DisplayName("Confirms each due booking; a failure does not stop the others")
    void confirmsDueBookingsAndContinuesAfterFailure() {
        CompletionAutoConfirmJob job = new CompletionAutoConfirmJob(completionService, Clock.fixed(NOW, ZoneOffset.UTC));
        when(completionService.findAutoConfirmCandidates(NOW)).thenReturn(List.of("b-1", "b-2", "b-3"));
        when(completionService.autoConfirm("b-1")).thenReturn(true);
        when(completionService.autoConfirm("b-2")).thenThrow(new IllegalStateException("conflict"));
        when(completionService.autoConfirm("b-3")).thenReturn(false);

        int confirmed = job.runOnce();

        assertEquals(1, confirmed);
        verify(completionService).autoConfirm("b-3");
    }

    @Test
    @DisplayName("Nothing due: nothing confirmed")
    void nothingDue() {
        CompletionAutoConfirmJob job = new CompletionAutoConfirmJob(completionService, Clock.fixed(NOW, ZoneOffset.UTC));
        when(completionService.findAutoConfirmCandidates(NOW)).thenReturn(List.of());

        assertEquals(0, job.runOnce());
    }
}
