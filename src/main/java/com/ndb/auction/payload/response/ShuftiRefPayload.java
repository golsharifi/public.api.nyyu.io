package com.ndb.auction.payload.response;

import com.ndb.auction.models.Shufti.ShuftiReference;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class ShuftiRefPayload extends ShuftiReference {

    public ShuftiRefPayload(ShuftiReference ref) {
        super(
                ref.getUserId(),
                ref.getReference(),
                ref.getVerificationType(),
                ref.getDocStatus(),
                ref.getAddrStatus(),
                ref.getConStatus(),
                ref.getSelfieStatus(),
                ref.getPending());

        // Set default values for new fields
        this.failed = false;
        this.error = null;
        this.failureReason = null;
        this.status = "setup";
    }

    // base64 for doc, addr, consent and selfie
    private String document;
    private String addr;
    private String consent;
    private String selfie;

    // New fields to match GraphQL schema
    private Boolean failed;
    private String error;
    private String failureReason;
    private String status;
}