package com.gamagerecruiters.marketplace.modules.listing.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Embedded optional add-on package belonging to a {@link ServiceListing}.
 */
public class AddOnItem {

    private String addOnId;
    private String title;
    private String description;
    private BigDecimal extraPrice;
    private Integer extraDurationMin;
    private boolean active = true;

    public AddOnItem() {
    }

    public AddOnItem(
            String addOnId,
            String title,
            BigDecimal extraPrice,
            Integer extraDurationMin,
            boolean active
    ) {
        this(addOnId, title, null, extraPrice, extraDurationMin, active);
    }

    public AddOnItem(
            String addOnId,
            String title,
            String description,
            BigDecimal extraPrice,
            Integer extraDurationMin,
            boolean active
    ) {
        this.addOnId = addOnId;
        this.title = title;
        this.description = description;
        this.extraPrice = extraPrice;
        this.extraDurationMin = extraDurationMin;
        this.active = active;
    }

    public String getAddOnId() {
        return addOnId;
    }

    public void setAddOnId(String addOnId) {
        this.addOnId = addOnId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public BigDecimal getExtraPrice() {
        return extraPrice;
    }

    public void setExtraPrice(BigDecimal extraPrice) {
        this.extraPrice = extraPrice;
    }

    public Integer getExtraDurationMin() {
        return extraDurationMin;
    }

    public void setExtraDurationMin(Integer extraDurationMin) {
        this.extraDurationMin = extraDurationMin;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AddOnItem other)) {
            return false;
        }
        return active == other.active
                && Objects.equals(addOnId, other.addOnId)
                && Objects.equals(title, other.title)
                && Objects.equals(description, other.description)
                && (extraPrice == null ? other.extraPrice == null
                : other.extraPrice != null && extraPrice.compareTo(other.extraPrice) == 0)
                && Objects.equals(extraDurationMin, other.extraDurationMin);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                addOnId,
                title,
                description,
                extraPrice == null ? null : extraPrice.stripTrailingZeros(),
                extraDurationMin,
                active
        );
    }
}
