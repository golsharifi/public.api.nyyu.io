package com.ndb.auction.security;

import com.ndb.auction.config.AppProperties;
import com.ndb.auction.service.user.UserDetailsImpl;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SecurityException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;

@Service
public class TokenProvider {
    private static final Logger logger = LoggerFactory.getLogger(TokenProvider.class);

    @Autowired
    private AppProperties appProperties;

    public TokenProvider(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    // Helper method to get the signing key
    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(appProperties.getAuth().getTokenSecret().getBytes());
    }

    public String createToken(UserDetailsImpl userPrincipal) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + appProperties.getAuth().getTokenExpirationMsec());

        return Jwts.builder()
                .subject(userPrincipal.getEmail()) // Updated method
                .issuedAt(new Date()) // Updated method
                .expiration(expiryDate) // Updated method
                .signWith(getSigningKey()) // Updated method
                .compact();
    }

    public Long getUserIdFromToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(getSigningKey()) // Updated method
                .build() // Required call
                .parseSignedClaims(token) // Updated method
                .getPayload(); // Updated method

        return Long.parseLong(claims.getSubject());
    }

    public boolean validateToken(String authToken) {
        try {
            Jwts.parser()
                    .verifyWith(getSigningKey()) // Updated method
                    .build() // Required call
                    .parseSignedClaims(authToken); // Updated method
            return true;
        } catch (SecurityException ex) { // Updated exception
            logger.error("Invalid JWT signature");
        } catch (MalformedJwtException ex) {
            logger.error("Invalid JWT token");
        } catch (ExpiredJwtException ex) {
            logger.error("Expired JWT token");
        } catch (UnsupportedJwtException ex) {
            logger.error("Unsupported JWT token");
        } catch (IllegalArgumentException ex) {
            logger.error("JWT claims string is empty.");
        }
        return false;
    }
}