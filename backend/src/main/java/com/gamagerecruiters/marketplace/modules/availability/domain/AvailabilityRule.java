package com.gamagerecruiters.marketplace.modules.availability.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One document per provider: weekly working hours and the buffer between bookings.
 */
@Document(collection = "availability_rules")
public class AvailabilityRule {

    @Id
    private String id;
    private String providerId;
    private List<WeeklyHours> weeklyHours = new ArrayList<>();
    private int bufferMinutes;
    private Instant createdAt;
    private Instant updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getProviderId() {
        return providerId;
    }

    public void setProviderId(String providerId) {
        this.providerId = providerId;
    }

    public List<WeeklyHours> getWeeklyHours() {
        return weeklyHours;
    }

    public void setWeeklyHours(List<WeeklyHours> weeklyHours) {
        this.weeklyHours = weeklyHours;
    }

    public int getBufferMinutes() {
        return bufferMinutes;
    }

    public void setBufferMinutes(int bufferMinutes) {
        this.bufferMinutes = bufferMinutes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
