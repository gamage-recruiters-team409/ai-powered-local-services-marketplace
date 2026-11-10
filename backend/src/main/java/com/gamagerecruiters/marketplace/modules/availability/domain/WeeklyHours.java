package com.gamagerecruiters.marketplace.modules.availability.domain;

import java.time.DayOfWeek;

/**
 * Working hours for one day of the week. Times are stored as "HH:mm" strings
 * (provider local time) to avoid timezone conversion when saving to MongoDB.
 */
public class WeeklyHours {

    private DayOfWeek dayOfWeek;
    private boolean enabled;
    private String startTime;
    private String endTime;

    public WeeklyHours() {
    }

    public WeeklyHours(DayOfWeek dayOfWeek, boolean enabled, String startTime, String endTime) {
        this.dayOfWeek = dayOfWeek;
        this.enabled = enabled;
        this.startTime = startTime;
        this.endTime = endTime;
    }

    public DayOfWeek getDayOfWeek() {
        return dayOfWeek;
    }

    public void setDayOfWeek(DayOfWeek dayOfWeek) {
        this.dayOfWeek = dayOfWeek;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    public String getEndTime() {
        return endTime;
    }

    public void setEndTime(String endTime) {
        this.endTime = endTime;
    }
}
