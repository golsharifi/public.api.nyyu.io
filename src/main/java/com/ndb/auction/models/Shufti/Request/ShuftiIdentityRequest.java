package com.ndb.auction.models.Shufti.Request;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ShuftiIdentityRequest {
    private String reference;
    private String country;
    private Document document;
}
