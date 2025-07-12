package com.ndb.auction.security.jwt;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.crypto.SecretKey;

import com.ndb.auction.models.user.User;
import com.ndb.auction.service.user.UserDetailsImpl;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SecurityException;

@Component
public class JwtUtils {
	private static final Logger logger = LoggerFactory.getLogger(JwtUtils.class);

	@Value("${app.jwtSecret}")
	private String jwtSecret;

	@Value("${app.jwtExpirationMs}")
	private int jwtExpirationMs;

	@Value("${zendesk.shared.secret}")
	private String ZENDESK_SHARED_SECRET;

	// Helper method to get the signing key - FIXED to use consistent key
	private SecretKey getSigningKey() {
		return Keys.hmacShaKeyFor(jwtSecret.getBytes());
	}

	public String generateJwtToken(Authentication authentication) {
		String email = "";
		try {
			UserDetailsImpl userPrincipal = (UserDetailsImpl) authentication.getPrincipal();
			email = userPrincipal.getEmail();
		} catch (Exception e) {
			OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();
			Map<String, Object> attributes = new HashMap<>(oAuth2User.getAttributes());
			email = (String) attributes.get("email");
		}

		return Jwts.builder()
				.subject(email)
				.issuedAt(new Date())
				.expiration(new Date((new Date()).getTime() + jwtExpirationMs))
				.signWith(getSigningKey())
				.compact();
	}

	public String generateJwtToken(String email) {
		return Jwts.builder()
				.subject(email)
				.issuedAt(new Date())
				.expiration(new Date((new Date()).getTime() + jwtExpirationMs))
				.signWith(getSigningKey())
				.compact();
	}

	// NEW METHOD: Generate JWT token from UserDetailsImpl
	public String generateJwtToken(UserDetailsImpl userDetails) {
		return Jwts.builder()
				.subject(userDetails.getEmail())
				.issuedAt(new Date())
				.expiration(new Date((new Date()).getTime() + jwtExpirationMs))
				.signWith(getSigningKey())
				.compact();
	}

	public String getEmailFromJwtToken(String token) {
		return Jwts.parser()
				.verifyWith(getSigningKey())
				.build()
				.parseSignedClaims(token)
				.getPayload()
				.getSubject();
	}

	public boolean validateJwtToken(String authToken) {
		try {
			logger.debug("Validating JWT token for: {}", getEmailFromJwtToken(authToken));
			Jwts.parser()
					.verifyWith(getSigningKey())
					.build()
					.parseSignedClaims(authToken);
			return true;
		} catch (SecurityException e) {
			logger.error("Invalid JWT signature: {}", e.getMessage());
		} catch (MalformedJwtException e) {
			logger.error("Invalid JWT token: {}", e.getMessage());
		} catch (ExpiredJwtException e) {
			logger.error("JWT token is expired: {}", e.getMessage());
		} catch (UnsupportedJwtException e) {
			logger.error("JWT token is unsupported: {}", e.getMessage());
		} catch (IllegalArgumentException e) {
			logger.error("JWT claims string is empty: {}", e.getMessage());
		} catch (Exception e) {
			logger.error("JWT validation failed: {}", e.getMessage());
		}
		return false;
	}

	public String generateZendeskJwtToken(User user) {
		var name = String.format("%s.%s", user.getAvatar().getPrefix(), user.getAvatar().getName());
		JWTClaimsSet jwtClaims = new JWTClaimsSet.Builder()
				.issueTime(new Date())
				.jwtID(UUID.randomUUID().toString())
				.claim("name", name)
				.claim("email", user.getEmail())
				.build();

		try {
			JWSSigner signer = new MACSigner(ZENDESK_SHARED_SECRET.getBytes());
			SignedJWT signedJWT = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), jwtClaims);
			signedJWT.sign(signer);
			return signedJWT.serialize();
		} catch (Exception e) {
			logger.error("Error generating Zendesk JWT token", e);
			return null;
		}
	}
}