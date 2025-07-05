package com.ndb.auction.exceptions;

import org.springframework.security.core.AuthenticationException;

public class ProviderMismatchException extends AuthenticationException {

    private final String userEmail;
    private final String originalProvider;
    private final String attemptedProvider;

    public ProviderMismatchException(String userEmail, String originalProvider, String attemptedProvider) {
        super(String.format("Account exists with %s. Please use %s to login.",
                capitalizeProvider(originalProvider), capitalizeProvider(originalProvider)));
        this.userEmail = userEmail;
        this.originalProvider = originalProvider;
        this.attemptedProvider = attemptedProvider;
    }

    public String getUserEmail() {
        return userEmail;
    }

    public String getOriginalProvider() {
        return originalProvider;
    }

    public String getAttemptedProvider() {
        return attemptedProvider;
    }

    private static String capitalizeProvider(String provider) {
        if (provider == null || provider.isEmpty()) {
            return provider;
        }
        return provider.substring(0, 1).toUpperCase() + provider.substring(1).toLowerCase();
    }
}