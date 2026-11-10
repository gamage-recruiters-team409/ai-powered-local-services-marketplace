package com.gamagerecruiters.marketplace.modules.availability.service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.auth.security.OwnershipChecker;
import com.gamagerecruiters.marketplace.modules.availability.domain.AvailabilityRule;
import com.gamagerecruiters.marketplace.modules.availability.domain.BlockedDate;
import com.gamagerecruiters.marketplace.modules.availability.domain.WeeklyHours;
import com.gamagerecruiters.marketplace.modules.availability.dto.AvailabilityResponse;
import com.gamagerecruiters.marketplace.modules.availability.dto.BlockedDateResponse;
import com.gamagerecruiters.marketplace.modules.availability.dto.CreateBlockedDateRequest;
import com.gamagerecruiters.marketplace.modules.availability.dto.UpdateAvailabilityRequest;
import com.gamagerecruiters.marketplace.modules.availability.dto.WeeklyHoursRequest;
import com.gamagerecruiters.marketplace.modules.availability.mapper.AvailabilityMapper;
import com.gamagerecruiters.marketplace.modules.availability.repository.AvailabilityRuleRepository;
import com.gamagerecruiters.marketplace.modules.availability.repository.BlockedDateRepository;

@Service
public class AvailabilityServiceImpl implements AvailabilityService {

    /** Default buffer between bookings (C-03). Will come from platform settings (WP09) later. */
    public static final int DEFAULT_BUFFER_MINUTES = 30;

    /** How far ahead a provider can block a date. */
    public static final int MAX_DAYS_AHEAD = 365;

    /** Business timezone used to decide what "today" is. */
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Colombo");

    /** Upsert attempts before a concurrent duplicate-key result is reported as a conflict. */
    static final int MAX_UPSERT_ATTEMPTS = 2;

    private final AvailabilityRuleRepository availabilityRuleRepository;
    private final BlockedDateRepository blockedDateRepository;
    private final MongoTemplate mongoTemplate;
    private final Clock clock;

    @Autowired
    public AvailabilityServiceImpl(AvailabilityRuleRepository availabilityRuleRepository,
                                   BlockedDateRepository blockedDateRepository,
                                   MongoTemplate mongoTemplate) {
        this(availabilityRuleRepository, blockedDateRepository, mongoTemplate, Clock.system(BUSINESS_ZONE));
    }

    AvailabilityServiceImpl(AvailabilityRuleRepository availabilityRuleRepository,
                            BlockedDateRepository blockedDateRepository,
                            MongoTemplate mongoTemplate,
                            Clock clock) {
        this.availabilityRuleRepository = availabilityRuleRepository;
        this.blockedDateRepository = blockedDateRepository;
        this.mongoTemplate = mongoTemplate;
        this.clock = clock;
    }

    @Override
    public AvailabilityResponse getAvailability(String providerId) {
        AvailabilityRule rule = availabilityRuleRepository.findByProviderId(providerId)
                .orElseGet(() -> defaultRule(providerId));
        return AvailabilityMapper.toResponse(rule);
    }

    /**
     * Creates or replaces the provider's schedule in one atomic upsert, so two first-time
     * requests at the same moment cannot both insert a document. The unique providerId index
     * keeps one schedule per provider.
     */
    @Override
    public AvailabilityResponse updateAvailability(String providerId, UpdateAvailabilityRequest request) {
        validateWeeklyHours(request.weeklyHours());

        List<WeeklyHours> days = request.weeklyHours().stream()
                .sorted(Comparator.comparing(WeeklyHoursRequest::dayOfWeek))
                .map(AvailabilityMapper::toDomain)
                .toList();
        Instant now = Instant.now(clock);

        Query query = Query.query(Criteria.where("providerId").is(providerId));
        Update update = new Update()
                .set("weeklyHours", new ArrayList<>(days))
                .set("bufferMinutes", request.bufferMinutes())
                .set("updatedAt", now)
                .setOnInsert("createdAt", now);
        FindAndModifyOptions options = FindAndModifyOptions.options().upsert(true).returnNew(true);

        return AvailabilityMapper.toResponse(upsertWithRetry(query, update, options));
    }

    private AvailabilityRule upsertWithRetry(Query query, Update update, FindAndModifyOptions options) {
        for (int attempt = 1; ; attempt++) {
            try {
                AvailabilityRule saved = mongoTemplate.findAndModify(query, update, options, AvailabilityRule.class);
                if (saved == null) {
                    throw new IllegalStateException("Availability upsert returned no document");
                }
                return saved;
            } catch (DuplicateKeyException e) {
                // Another request inserted this provider's schedule at the same time.
                // Retrying turns the insert into a normal update of that document.
                if (attempt >= MAX_UPSERT_ATTEMPTS) {
                    throw new ResourceConflictException("AVAILABILITY_UPDATE_CONFLICT",
                            "Availability was updated at the same time. Please try again.");
                }
            }
        }
    }

    @Override
    public List<BlockedDateResponse> getBlockedDates(String providerId, String from, String to) {
        LocalDate today = LocalDate.now(clock);
        LocalDate fromDate = (from == null || from.isBlank()) ? today : parseDate(from, "from");
        LocalDate toDate = (to == null || to.isBlank()) ? fromDate.plusDays(MAX_DAYS_AHEAD) : parseDate(to, "to");

        if (toDate.isBefore(fromDate)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE", "'to' must be on or after 'from'");
        }
        if (ChronoUnit.DAYS.between(fromDate, toDate) > MAX_DAYS_AHEAD) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE",
                    "Date range cannot be longer than " + MAX_DAYS_AHEAD + " days");
        }

        return blockedDateRepository
                .findInRange(providerId, fromDate.toString(), toDate.toString(), Sort.by(Sort.Direction.ASC, "date"))
                .stream()
                .map(AvailabilityMapper::toResponse)
                .toList();
    }

    @Override
    public BlockedDateResponse createBlockedDate(String providerId, CreateBlockedDateRequest request) {
        LocalDate date = parseDate(request.date(), "date");
        LocalDate today = LocalDate.now(clock);

        if (date.isBefore(today)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BLOCKED_DATE", "Cannot block a past date");
        }
        if (date.isAfter(today.plusDays(MAX_DAYS_AHEAD))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BLOCKED_DATE",
                    "Cannot block a date more than " + MAX_DAYS_AHEAD + " days ahead");
        }

        String dateText = date.toString();
        if (blockedDateRepository.existsByProviderIdAndDate(providerId, dateText)) {
            throw new ResourceConflictException("BLOCKED_DATE_EXISTS", "This date is already blocked");
        }

        BlockedDate blockedDate = new BlockedDate();
        blockedDate.setProviderId(providerId);
        blockedDate.setDate(dateText);
        blockedDate.setReason(request.reason());
        blockedDate.setNote(request.note() == null ? null : request.note().trim());
        blockedDate.setCreatedAt(Instant.now(clock));

        try {
            return AvailabilityMapper.toResponse(blockedDateRepository.save(blockedDate));
        } catch (DuplicateKeyException e) {
            // Two requests for the same date at the same time: the unique index stops the second one.
            throw new ResourceConflictException("BLOCKED_DATE_EXISTS", "This date is already blocked");
        }
    }

    @Override
    public void deleteBlockedDate(String providerId, String blockedDateId) {
        BlockedDate blockedDate = blockedDateRepository.findById(blockedDateId)
                .orElseThrow(() -> new ResourceNotFoundException("BLOCKED_DATE_NOT_FOUND", "Blocked date not found"));

        OwnershipChecker.assertOwner(providerId, blockedDate.getProviderId());

        blockedDateRepository.delete(blockedDate);
    }

    private void validateWeeklyHours(List<WeeklyHoursRequest> weeklyHours) {
        Set<DayOfWeek> seen = EnumSet.noneOf(DayOfWeek.class);

        for (WeeklyHoursRequest day : weeklyHours) {
            if (!seen.add(day.dayOfWeek())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_WEEKLY_HOURS",
                        "Each day of the week must appear only once: " + day.dayOfWeek());
            }
            if (!day.enabled()) {
                continue;
            }
            if (day.startTime() == null || day.endTime() == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_WEEKLY_HOURS",
                        "startTime and endTime are required for an enabled day: " + day.dayOfWeek());
            }
            if (!LocalTime.parse(day.startTime()).isBefore(LocalTime.parse(day.endTime()))) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_WEEKLY_HOURS",
                        "startTime must be before endTime: " + day.dayOfWeek());
            }
        }
    }

    private LocalDate parseDate(String value, String fieldName) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE",
                    "'" + fieldName + "' must be a valid date in yyyy-MM-dd format");
        }
    }

    /** Returned when the provider has not saved a schedule yet. Not stored. */
    private AvailabilityRule defaultRule(String providerId) {
        AvailabilityRule rule = new AvailabilityRule();
        rule.setProviderId(providerId);
        List<WeeklyHours> days = new ArrayList<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            days.add(new WeeklyHours(day, false, null, null));
        }
        rule.setWeeklyHours(days);
        rule.setBufferMinutes(DEFAULT_BUFFER_MINUTES);
        return rule;
    }
}
