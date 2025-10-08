package com.ndb.auction.models;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CountrySetting {
    private int id;
    private String countryCode;
    private boolean verificationExempt;
    private boolean blocked;
    private String blockReason;
    private String notes;
    private int createdBy;
    private int updatedBy;
    private long createDate;
    private long updateDate;

    // Constructor for easy creation
    public CountrySetting(String countryCode, boolean verificationExempt, boolean blocked, String blockReason) {
        this.countryCode = countryCode;
        this.verificationExempt = verificationExempt;
        this.blocked = blocked;
        this.blockReason = blockReason;
    }
}