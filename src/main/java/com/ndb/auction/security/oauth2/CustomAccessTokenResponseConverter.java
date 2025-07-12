package com.ndb.auction.security.oauth2;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class CustomAccessTokenResponseConverter implements Converter<Map<String, Object>, OAuth2AccessTokenResponse> {

	@Override
	public OAuth2AccessTokenResponse convert(Map<String, Object> tokenResponseParameters) {
		String accessToken = (String) tokenResponseParameters.get("access_token");

		if (!StringUtils.hasText(accessToken)) {
			return null;
		}

		OAuth2AccessTokenResponse.Builder builder = OAuth2AccessTokenResponse.withToken(accessToken);

		// Set token type
		OAuth2AccessToken.TokenType accessTokenType = OAuth2AccessToken.TokenType.BEARER;
		if (tokenResponseParameters.containsKey("token_type")) {
			String tokenType = (String) tokenResponseParameters.get("token_type");
			if (OAuth2AccessToken.TokenType.BEARER.getValue().equalsIgnoreCase(tokenType)) {
				accessTokenType = OAuth2AccessToken.TokenType.BEARER;
			}
		}
		builder.tokenType(accessTokenType);

		// Set expiration
		if (tokenResponseParameters.containsKey("expires_in")) {
			Object expiresInObj = tokenResponseParameters.get("expires_in");
			long expiresIn = 0;
			if (expiresInObj instanceof Number) {
				expiresIn = ((Number) expiresInObj).longValue();
			} else if (expiresInObj instanceof String) {
				try {
					expiresIn = Long.parseLong((String) expiresInObj);
				} catch (NumberFormatException e) {
					// Default to 0 if parsing fails
				}
			}
			builder.expiresIn(expiresIn);
		}

		// Set refresh token
		if (tokenResponseParameters.containsKey("refresh_token")) {
			String refreshToken = (String) tokenResponseParameters.get("refresh_token");
			if (StringUtils.hasText(refreshToken)) {
				builder.refreshToken(refreshToken);
			}
		}

		// Set scopes
		if (tokenResponseParameters.containsKey("scope")) {
			String scope = (String) tokenResponseParameters.get("scope");
			if (StringUtils.hasText(scope)) {
				Set<String> scopes = new LinkedHashSet<>(Arrays.asList(scope.split("\\s+")));
				builder.scopes(scopes);
			}
		}

		// Add any additional parameters
		builder.additionalParameters(tokenResponseParameters);

		return builder.build();
	}
}