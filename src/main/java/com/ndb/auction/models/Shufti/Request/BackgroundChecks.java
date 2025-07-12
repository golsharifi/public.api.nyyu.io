package com.ndb.auction.models.Shufti.Request;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BackgroundChecks {
    private Names name;
    private String first_name;
    private String last_name;
    private String dob;

    public BackgroundChecks(Names names) {
        this.name = names;
        this.first_name = names.getFirst_name();
        this.last_name = names.getLast_name();
        // this.dob = names.getDob();
    }
}