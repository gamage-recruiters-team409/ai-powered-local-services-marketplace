package com.gamagerecruiters.marketplace.modules.booking.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;
import com.gamagerecruiters.marketplace.modules.availability.dto.BlockedDateResponse;
import com.gamagerecruiters.marketplace.modules.availability.dto.ProviderSchedule;
import com.gamagerecruiters.marketplace.modules.availability.dto.WeeklyHoursResponse;
import com.gamagerecruiters.marketplace.modules.availability.service.AvailabilityFacade;
import com.gamagerecruiters.marketplace.modules.booking.domain.Booking;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.domain.CalendarFeedToken;
import com.gamagerecruiters.marketplace.modules.booking.dto.CalendarResponse;
import com.gamagerecruiters.marketplace.modules.booking.dto.CalendarSubscriptionResponse;
import com.gamagerecruiters.marketplace.modules.booking.repository.BookingRepository;
import com.gamagerecruiters.marketplace.modules.booking.repository.CalendarFeedTokenRepository;

@Service
public class BookingCalendarServiceImpl implements BookingCalendarService {

    static final int MAX_RANGE_DAYS = 42;
    /** Feeds and exports also show the last 30 days, so recent jobs stay in the calendar. */
    static final Duration HISTORY = Duration.ofDays(30);
    static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    static final String FEED_PATH = "/api/v1/calendar/feed/";

    /** Shown in the calendar view (legend: confirmed, in progress, awaiting payment, completed). */
    static final Set<BookingStatus> CALENDAR_STATUSES = EnumSet.of(BookingStatus.AWAITING_PAYMENT,
            BookingStatus.CONFIRMED, BookingStatus.IN_PROGRESS, BookingStatus.COMPLETION_PENDING,
            BookingStatus.COMPLETED, BookingStatus.DISPUTED);
    /** Put in .ics files and feeds; AWAITING_PAYMENT is marked TENTATIVE. */
    static final Set<BookingStatus> ICS_STATUSES = EnumSet.of(BookingStatus.AWAITING_PAYMENT,
            BookingStatus.CONFIRMED, BookingStatus.IN_PROGRESS, BookingStatus.COMPLETION_PENDING,
            BookingStatus.COMPLETED);

    private static final DateTimeFormatter LOCAL_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(SlotServiceImpl.BUSINESS_ZONE);
    private static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern("HH:mm")
            .withZone(SlotServiceImpl.BUSINESS_ZONE);

    private final BookingRepository bookingRepository;
    private final CalendarFeedTokenRepository tokenRepository;
    private final AvailabilityFacade availabilityFacade;
    private final String feedBaseUrl;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public BookingCalendarServiceImpl(BookingRepository bookingRepository, CalendarFeedTokenRepository tokenRepository,
                                      AvailabilityFacade availabilityFacade,
                                      @Value("${app.calendar.feed-base-url:http://localhost:8080}") String feedBaseUrl) {
        this(bookingRepository, tokenRepository, availabilityFacade, feedBaseUrl, Clock.systemUTC());
    }

    BookingCalendarServiceImpl(BookingRepository bookingRepository, CalendarFeedTokenRepository tokenRepository,
                               AvailabilityFacade availabilityFacade, String feedBaseUrl, Clock clock) {
        this.bookingRepository = bookingRepository;
        this.tokenRepository = tokenRepository;
        this.availabilityFacade = availabilityFacade;
        this.feedBaseUrl = feedBaseUrl.endsWith("/") ? feedBaseUrl.substring(0, feedBaseUrl.length() - 1)
                : feedBaseUrl;
        this.clock = clock;
    }

    // ---------- calendar view ----------

    @Override
    public CalendarResponse calendar(String fromText, String toText) {
        String providerId = currentUserId();
        LocalDate from = parseDate(fromText, "from");
        LocalDate to = parseDate(toText, "to");
        if (to.isBefore(from)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE", "'to' must be on or after 'from'");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_RANGE_DAYS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE",
                    "The range can be at most " + MAX_RANGE_DAYS + " days");
        }

        ProviderSchedule schedule = availabilityFacade.getSchedule(providerId);
        Map<DayOfWeek, WeeklyHoursResponse> hours = schedule.weeklyHours().stream()
                .collect(Collectors.toMap(WeeklyHoursResponse::dayOfWeek, Function.identity(), (a, b) -> a));
        Map<String, BlockedDateResponse> blocked = availabilityFacade.getBlockedDates(providerId, from, to).stream()
                .collect(Collectors.toMap(BlockedDateResponse::date, Function.identity(), (a, b) -> a));

        List<CalendarResponse.Day> days = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            WeeklyHoursResponse h = hours.get(d.getDayOfWeek());
            boolean working = h != null && h.enabled() && h.startTime() != null && h.endTime() != null;
            BlockedDateResponse block = blocked.get(d.toString());
            days.add(new CalendarResponse.Day(d.toString(), d.getDayOfWeek().name(), working && block == null,
                    working ? h.startTime() : null, working ? h.endTime() : null, block != null,
                    block == null || block.reason() == null ? null : block.reason().name()));
        }

        Instant rangeStart = from.atStartOfDay(SlotServiceImpl.BUSINESS_ZONE).toInstant();
        Instant rangeEnd = to.plusDays(1).atStartOfDay(SlotServiceImpl.BUSINESS_ZONE).toInstant();
        List<CalendarResponse.Event> events = bookingRepository
                .findByProviderIdAndStatusInAndSlotStartLessThanAndSlotEndGreaterThan(providerId, CALENDAR_STATUSES,
                        rangeEnd, rangeStart).stream()
                .sorted((a, b) -> a.getSlotStart().compareTo(b.getSlotStart()))
                .map(b -> new CalendarResponse.Event(b.getId(), b.getRef(), b.getStatus(), serviceTitle(b),
                        b.getCustomerId(), b.getSlotStart(), b.getSlotEnd(), LOCAL_DATE.format(b.getSlotStart()),
                        LOCAL_TIME.format(b.getSlotStart()), LOCAL_TIME.format(b.getSlotEnd())))
                .toList();

        return new CalendarResponse(from.toString(), to.toString(), SlotServiceImpl.BUSINESS_ZONE.getId(), days,
                events);
    }

    // ---------- .ics export ----------

    @Override
    public String exportIcs() {
        return ics(currentUserId(), currentRole());
    }

    private String ics(String userId, String role) {
        Instant now = Instant.now(clock);
        Instant after = now.minus(HISTORY);
        List<Booking> bookings = "PROVIDER".equals(role)
                ? bookingRepository.findTop500ByProviderIdAndStatusInAndSlotEndGreaterThanOrderBySlotStartAsc(userId,
                        ICS_STATUSES, after)
                : bookingRepository.findTop500ByCustomerIdAndStatusInAndSlotEndGreaterThanOrderBySlotStartAsc(userId,
                        ICS_STATUSES, after);
        List<IcsWriter.Event> events = bookings.stream()
                .filter(b -> b.getSlotStart() != null && b.getSlotEnd() != null)
                .map(b -> new IcsWriter.Event(b.getId() + "@localserve", b.getSlotStart(), b.getSlotEnd(),
                        serviceTitle(b) + " (" + b.getRef() + ")",
                        "Booking " + b.getRef() + "\nStatus: " + b.getStatus().name(),
                        b.getAddress() == null ? null : b.getAddress().getDistrict(),
                        b.getStatus() != BookingStatus.AWAITING_PAYMENT))
                .toList();
        return IcsWriter.write("LocalServe bookings", events, now);
    }

    // ---------- private subscription link ----------

    @Override
    public CalendarSubscriptionResponse getSubscription() {
        return tokenRepository.findByUserId(currentUserId())
                .map(t -> new CalendarSubscriptionResponse(true, null, null, t.getCreatedAt(), t.getLastUsedAt()))
                .orElse(new CalendarSubscriptionResponse(false, null, null, null, null));
    }

    @Override
    public CalendarSubscriptionResponse createOrResetSubscription() {
        String userId = currentUserId();
        String role = currentRole();
        tokenRepository.deleteByUserId(userId);

        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        CalendarFeedToken feedToken = new CalendarFeedToken();
        feedToken.setUserId(userId);
        feedToken.setRole(role);
        feedToken.setTokenHash(hash(token));
        feedToken.setCreatedAt(Instant.now(clock));
        try {
            tokenRepository.save(feedToken);
        } catch (DuplicateKeyException e) {
            throw new ResourceConflictException("CALENDAR_LINK_BUSY",
                    "The link was changed at the same time. Please try again.");
        }
        String url = feedBaseUrl + FEED_PATH + token + ".ics";
        return new CalendarSubscriptionResponse(true, url, url.replaceFirst("^https?://", "webcal://"),
                feedToken.getCreatedAt(), null);
    }

    @Override
    public void revokeSubscription() {
        tokenRepository.deleteByUserId(currentUserId());
    }

    @Override
    public String feed(String token) {
        if (token == null || !TOKEN.matcher(token).matches()) {
            throw feedNotFound();
        }
        CalendarFeedToken feedToken = tokenRepository.findByTokenHash(hash(token)).orElseThrow(this::feedNotFound);
        Instant now = Instant.now(clock);
        // Calendar apps poll often: record use at most once an hour.
        // Conditional field update (never save()): a link revoked or reset while this request runs is not
        // written back, so a revoked secret can never become valid again.
        if (feedToken.getLastUsedAt() == null || feedToken.getLastUsedAt().isBefore(now.minus(Duration.ofHours(1)))) {
            tokenRepository.touchLastUsed(feedToken.getId(), feedToken.getTokenHash(), now);
        }
        return ics(feedToken.getUserId(), feedToken.getRole());
    }

    // ---------- helpers ----------

    private ResourceNotFoundException feedNotFound() {
        return new ResourceNotFoundException("CALENDAR_FEED_NOT_FOUND", "Calendar link not found");
    }

    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String serviceTitle(Booking b) {
        return b.getPriceSnapshot() == null || b.getPriceSnapshot().serviceTitle() == null ? "Booking"
                : b.getPriceSnapshot().serviceTitle();
    }

    private static LocalDate parseDate(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE", name + " is required (yyyy-MM-dd)");
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE", name + " must be in yyyy-MM-dd format");
        }
    }

    private static String currentUserId() {
        String userId = CurrentUser.getUserId();
        if (userId == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required");
        }
        return userId;
    }

    private static String currentRole() {
        Role role = CurrentUser.getRole();
        return role == Role.PROVIDER ? "PROVIDER" : "CUSTOMER";
    }
}
