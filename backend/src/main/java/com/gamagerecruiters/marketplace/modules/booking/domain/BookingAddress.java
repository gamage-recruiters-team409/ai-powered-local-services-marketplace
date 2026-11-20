package com.gamagerecruiters.marketplace.modules.booking.domain;

/**
 * Service address stored on the booking. Shown in full only to the customer, and to the
 * provider once the booking status is contact-visible (CONFIRMED and later).
 * Before that, other parties see the district only.
 */
public class BookingAddress {

    private String line1;
    private String line2;
    private String city;
    private String district;
    private String postalCode;

    public BookingAddress() {
    }

    public BookingAddress(String line1, String line2, String city, String district, String postalCode) {
        this.line1 = line1;
        this.line2 = line2;
        this.city = city;
        this.district = district;
        this.postalCode = postalCode;
    }

    public String getLine1() {
        return line1;
    }

    public void setLine1(String line1) {
        this.line1 = line1;
    }

    public String getLine2() {
        return line2;
    }

    public void setLine2(String line2) {
        this.line2 = line2;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getDistrict() {
        return district;
    }

    public void setDistrict(String district) {
        this.district = district;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public void setPostalCode(String postalCode) {
        this.postalCode = postalCode;
    }
}
