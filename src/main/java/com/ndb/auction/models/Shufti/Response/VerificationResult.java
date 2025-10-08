package com.ndb.auction.models.Shufti.Response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.ndb.auction.utils.VerificationResultDeserializer;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@JsonIgnoreProperties(ignoreUnknown = true)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@JsonDeserialize(using = VerificationResultDeserializer.class)
public class VerificationResult {
    private DocumentVerification document;
    private AddressVerification address;
    private ConsentVerification consent;
    private Integer face;

    // Additional fields that might be in the response
    private String status;
    private String message;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DocumentVerification {
        private Integer document;
        private Integer name;
        private Integer dob;
        private Integer expiry_date;
        private Integer issue_date;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AddressVerification {
        private Integer address_document;
        private Integer full_address;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConsentVerification {
        private Integer consent;
    }
}