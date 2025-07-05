package com.ndb.auction.models.Shufti.Response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true) // This will ignore any unexpected fields
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ShuftiResponse {
    private String reference;
    private String event;
    private String error;
    private String message;
    private VerificationResult verification_result;
    private VerificationData verification_data;
    private JsonNode proofs;
    private String country;
    private String declined_reason;
    private AdditionalData additional_data;

}