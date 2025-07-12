package com.ndb.auction.exceptions;

public class VerificationRequiredException extends RuntimeException {
    public VerificationRequiredException(String message) {
        super(message);
    }

    public VerificationRequiredException(String message, Throwable cause) {
        super(message, cause);
    }
}