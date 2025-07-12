package com.ndb.auction.exceptions;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import graphql.ErrorClassification;
import graphql.ErrorType;
import graphql.GraphQLError;
import graphql.language.SourceLocation;

public class PaymentException extends RuntimeException implements GraphQLError {

    private String invalidField;

    // Constructor with message only (new constructor to fix the compilation error)
    public PaymentException(String message) {
        super(message);
        this.invalidField = null; // Set to null when no specific field is invalid
    }

    // Original constructor with message and invalidField
    public PaymentException(String message, String invalidField) {
        super(message);
        this.invalidField = invalidField;
    }

    @Override
    public String getMessage() {
        return super.getMessage();
    }

    @Override
    public List<Object> getPath() {
        return null;
    }

    @Override
    public List<SourceLocation> getLocations() {
        return null;
    }

    @Override
    public ErrorClassification getErrorType() {
        return ErrorType.ValidationError;
    }

    @Override
    public Map<String, Object> getExtensions() {
        // Return empty map if invalidField is null, otherwise return the field
        if (invalidField == null) {
            return Collections.emptyMap();
        }
        return Collections.singletonMap("invalidField", invalidField);
    }
}