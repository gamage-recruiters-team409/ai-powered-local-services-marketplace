package com.gamagerecruiters.marketplace.modules.messaging.controller;

import java.time.Instant;
import java.time.format.DateTimeParseException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gamagerecruiters.marketplace.common.util.ApiPaths;
import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.messaging.dto.ConversationPageResponse;
import com.gamagerecruiters.marketplace.modules.messaging.dto.MessagePageResponse;
import com.gamagerecruiters.marketplace.modules.messaging.dto.MessagePollingResponse;
import com.gamagerecruiters.marketplace.modules.messaging.dto.MessageResponse;
import com.gamagerecruiters.marketplace.modules.messaging.dto.SendMessageRequest;
import com.gamagerecruiters.marketplace.modules.messaging.service.MessageService;

import jakarta.validation.Valid;

@RestController
@RequestMapping(ApiPaths.API_V1)
public class MessagingController {

    private final MessageService messageService;

    public MessagingController(MessageService messageService) {
        this.messageService = messageService;
    }

    @GetMapping("/conversations")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'PROVIDER')")
    public ResponseEntity<ConversationPageResponse> listConversations(
            @RequestParam(defaultValue = "0") String page,
            @RequestParam(defaultValue = "20") String size) {
        int parsedPage = parsePage(page);
        int parsedSize = parseSize(size);
        return ResponseEntity.ok(messageService.listConversations(parsedPage, parsedSize));
    }

    @GetMapping(value = "/bookings/{bookingId}/messages", params = "!since")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'PROVIDER')")
    public ResponseEntity<MessagePageResponse> listMessages(
            @PathVariable String bookingId,
            @RequestParam(defaultValue = "0") String page,
            @RequestParam(defaultValue = "20") String size,
            @RequestParam(required = false) String afterId) {
        validateAfterId(afterId, false);
        return ResponseEntity.ok(messageService.listMessages(bookingId, parsePage(page), parseSize(size)));
    }

    /**
     * Returns messages created after a cursor in stable {@code (createdAt, id)} order.
     * Clients must send {@code afterId} together with {@code since} when continuing from a prior
     * response, using {@code nextSince} and {@code nextMessageId} returned by that response.
     */
    @GetMapping(value = "/bookings/{bookingId}/messages", params = "since")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'PROVIDER')")
    public ResponseEntity<MessagePollingResponse> pollMessages(
            @PathVariable String bookingId,
            @RequestParam String since,
            @RequestParam(required = false) String afterId) {
        validateAfterId(afterId, true);
        Instant parsedSince;
        try {
            parsedSince = Instant.parse(since);
        } catch (DateTimeParseException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SINCE", "Invalid since timestamp");
        }
        return ResponseEntity.ok(messageService.listMessagesSince(bookingId, parsedSince, afterId));
    }

    @PostMapping("/bookings/{bookingId}/messages")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'PROVIDER')")
    public ResponseEntity<MessageResponse> sendMessage(
            @PathVariable String bookingId,
            @Valid @RequestBody SendMessageRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(messageService.sendMessage(bookingId, request.body()));
    }

    private int parsePage(String value) {
        try {
            int page = Integer.parseInt(value);
            if (page < 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGINATION", "Invalid page");
            }
            return page;
        } catch (NumberFormatException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGINATION", "Invalid page");
        }
    }

    private int parseSize(String value) {
        try {
            int size = Integer.parseInt(value);
            if (size <= 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGINATION", "Invalid size");
            }
            return size;
        } catch (NumberFormatException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGINATION", "Invalid size");
        }
    }

    private void validateAfterId(String afterId, boolean sinceProvided) {
        if (afterId == null || afterId.isBlank()) {
            return;
        }
        if (!sinceProvided || afterId.length() > 64) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "Invalid polling cursor");
        }
    }
}
