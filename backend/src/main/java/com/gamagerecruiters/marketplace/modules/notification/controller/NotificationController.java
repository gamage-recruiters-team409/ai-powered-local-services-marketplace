package com.gamagerecruiters.marketplace.modules.notification.controller;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.util.ApiPaths;
import com.gamagerecruiters.marketplace.modules.notification.dto.NotificationPageResponse;
import com.gamagerecruiters.marketplace.modules.notification.dto.NotificationResponse;
import com.gamagerecruiters.marketplace.modules.notification.dto.UnreadCountResponse;
import com.gamagerecruiters.marketplace.modules.notification.service.NotificationInboxService;

@RestController @RequestMapping(ApiPaths.API_V1) @PreAuthorize("isAuthenticated()")
public class NotificationController {
    private final NotificationInboxService inbox;
    public NotificationController(NotificationInboxService inbox) { this.inbox = inbox; }
    @GetMapping("/notifications") public ResponseEntity<NotificationPageResponse> list(@RequestParam(defaultValue = "0") String page,
            @RequestParam(defaultValue = "20") String size, @RequestParam(defaultValue = "false") String unread) {
        return ResponseEntity.ok(inbox.listMine(parseUnread(unread), parsePage(page), parseSize(size)));
    }
    @PatchMapping("/notifications/{id}/read") public ResponseEntity<NotificationResponse> markRead(@PathVariable String id) { return ResponseEntity.ok(inbox.markRead(id)); }
    @PatchMapping("/notifications/read-all") public ResponseEntity<Map<String, Long>> markAllRead() { return ResponseEntity.ok(Map.of("updated", inbox.markAllRead())); }
    @GetMapping("/notifications/unread-count") public ResponseEntity<UnreadCountResponse> unreadCount() { return ResponseEntity.ok(new UnreadCountResponse(inbox.unreadCount())); }
    private int parsePage(String value) { return parse(value, false, "page"); }
    private int parseSize(String value) { return parse(value, true, "size"); }
    private int parse(String value, boolean positive, String label) { try { int parsed = Integer.parseInt(value); if (parsed < 0 || positive && parsed == 0) throw invalidPagination(label); return parsed; } catch (NumberFormatException ex) { throw invalidPagination(label); } }
    private ApiException invalidPagination(String label) { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGINATION", "Invalid " + label); }
    private boolean parseUnread(String value) { if ("true".equals(value)) return true; if ("false".equals(value)) return false; throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FILTER", "Invalid unread filter"); }
}
