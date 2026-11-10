package com.gamagerecruiters.marketplace.modules.booking.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;

import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;

import com.gamagerecruiters.marketplace.modules.booking.domain.Booking;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingParticipants;
import com.gamagerecruiters.marketplace.modules.booking.initializer.BookingIndexInitializer;
import com.gamagerecruiters.marketplace.modules.booking.repository.BookingRepository;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingFacade;

/**
 * Runs the booking foundation against a real MongoDB (Testcontainers):
 * index creation on a fresh database, BookingFacade queries on real data,
 * unique booking reference and optimistic locking.
 * Requires Docker. Skip explicitly with -DSKIP_DOCKER_TESTS=true.
 */
@SpringBootTest
@ActiveProfiles("integration-test")
@Tag("integration")
@DisabledIfSystemProperty(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
@DisabledIfEnvironmentVariable(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
class BookingMongoIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(BookingMongoIntegrationTest.class);

    static MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:7.0");

    @DynamicPropertySource
    static void configureMongoUri(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> {
            if (!mongoDBContainer.isRunning()) {
                mongoDBContainer.start();
            }
            return mongoDBContainer.getReplicaSetUrl();
        });
    }

    @BeforeAll
    static void startContainer() {
        if (!mongoDBContainer.isRunning()) {
            mongoDBContainer.start();
        }
    }

    @AfterAll
    static void stopContainer() {
        if (mongoDBContainer.isRunning()) {
            mongoDBContainer.stop();
        }
    }

    @Autowired
    private BookingFacade bookingFacade;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private BookingIndexInitializer indexInitializer;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void cleanData() {
        // Remove documents only; indexes stay in place.
        mongoTemplate.remove(new Query(), Booking.class);
    }

    private Booking booking(String customerId, BookingStatus status) {
        Booking booking = new Booking();
        booking.setRef("BK-TEST-" + UUID.randomUUID());
        booking.setCustomerId(customerId);
        booking.setProviderId("provider-1");
        booking.setListingId("listing-1");
        booking.setSlotStart(Instant.parse("2026-12-01T04:30:00Z"));
        booking.setSlotEnd(Instant.parse("2026-12-01T06:30:00Z"));
        booking.setStatus(status);
        booking.setCreatedAt(Instant.now());
        booking.setUpdatedAt(Instant.now());
        return booking;
    }

    @Test
    @DisplayName("Real MongoDB, fresh database: bookings collection and its 3 indexes are created from nothing")
    void freshDatabaseCreatesCollectionAndIndexes() {
        mongoTemplate.dropCollection(Booking.class);
        assertThat(mongoTemplate.collectionExists("bookings")).isFalse();

        indexInitializer.initializeIndexes();

        assertThat(mongoTemplate.collectionExists("bookings")).isTrue();

        Document ref = findIndex("ref_1");
        Document providerSlot = findIndex("providerId_1_slotStart_1");
        Document customerStatus = findIndex("customerId_1_status_1");
        log.info("Fresh database - bookings index created: {}", ref.toJson());
        log.info("Fresh database - bookings index created: {}", providerSlot.toJson());
        log.info("Fresh database - bookings index created: {}", customerStatus.toJson());

        assertThat(new ArrayList<>(ref.get("key", Document.class).keySet())).containsExactly("ref");
        assertThat(ref.getBoolean("unique")).isTrue();
        assertThat(new ArrayList<>(providerSlot.get("key", Document.class).keySet()))
                .containsExactly("providerId", "slotStart");
        assertThat(providerSlot.getBoolean("unique", false)).isFalse();
        assertThat(new ArrayList<>(customerStatus.get("key", Document.class).keySet()))
                .containsExactly("customerId", "status");
        assertThat(customerStatus.getBoolean("unique", false)).isFalse();
    }

    @Test
    @DisplayName("Real MongoDB: hasOpenBookingsForCustomer is true for each open status and false for closed ones")
    void hasOpenBookingsForCustomerOnRealData() {
        for (BookingStatus status : BookingStatus.values()) {
            mongoTemplate.remove(new Query(), Booking.class);
            bookingRepository.save(booking("customer-1", status));

            boolean open = bookingFacade.hasOpenBookingsForCustomer("customer-1");
            log.info("hasOpenBookingsForCustomer with status {} -> {}", status, open);

            assertThat(open).as("status %s", status).isEqualTo(BookingStatus.OPEN_STATUSES.contains(status));
        }

        assertThat(bookingFacade.hasOpenBookingsForCustomer("customer-without-bookings")).isFalse();
    }

    @Test
    @DisplayName("Real MongoDB: getBookingParticipants returns the stored IDs and status")
    void getBookingParticipantsOnRealData() {
        Booking saved = bookingRepository.save(booking("customer-2", BookingStatus.CONFIRMED));

        BookingParticipants participants = bookingFacade.getBookingParticipants(saved.getId());

        assertThat(participants).isEqualTo(new BookingParticipants(
                saved.getId(), null, "listing-1", "customer-2", "provider-1", BookingStatus.CONFIRMED));
    }

    @Test
    @DisplayName("Real MongoDB: duplicate booking reference is rejected by the unique index")
    void duplicateRefIsRejected() {
        Booking first = booking("customer-3", BookingStatus.PENDING_PROVIDER);
        first.setRef("BK-DUPLICATE-1");
        bookingRepository.save(first);

        Booking second = booking("customer-4", BookingStatus.PENDING_PROVIDER);
        second.setRef("BK-DUPLICATE-1");

        assertThatThrownBy(() -> bookingRepository.save(second)).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("Real MongoDB: optimistic locking stops a stale copy from overwriting a newer status change")
    void optimisticLockingRejectsStaleUpdate() {
        Booking saved = bookingRepository.save(booking("customer-5", BookingStatus.PENDING_PROVIDER));

        Booking copyA = bookingRepository.findById(saved.getId()).orElseThrow();
        Booking copyB = bookingRepository.findById(saved.getId()).orElseThrow();

        copyA.changeStatus(BookingStatus.AWAITING_PAYMENT, "provider-1", null, Instant.now());
        bookingRepository.save(copyA);

        copyB.changeStatus(BookingStatus.DECLINED, "provider-1", "Not available", Instant.now());
        assertThatThrownBy(() -> bookingRepository.save(copyB))
                .isInstanceOf(OptimisticLockingFailureException.class);

        Booking stored = bookingRepository.findById(saved.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(BookingStatus.AWAITING_PAYMENT);
        assertThat(stored.getTimeline()).hasSize(1);
    }

    private Document findIndex(String indexName) {
        for (Document index : mongoTemplate.getCollection("bookings").listIndexes()) {
            if (indexName.equals(index.getString("name"))) {
                return index;
            }
        }
        throw new AssertionError("Index " + indexName + " not found on bookings");
    }
}
