package com.ndb.auction.security.oauth2.user;

import java.util.Map;

public class LinkedInOAuth2UserInfo extends OAuth2UserInfo {

	public LinkedInOAuth2UserInfo(Map<String, Object> attributes) {
		super(attributes);
	}

	@Override
	public String getId() {
		return (String) attributes.get("id");
	}

	@Override
	public String getName() {
		// Try constructed name first
		String fullName = (String) attributes.get("name");
		if (fullName != null && !fullName.trim().isEmpty()) {
			return fullName;
		}

		// Construct from parts
		String firstName = getFirstName();
		String lastName = getLastName();

		if (firstName != null && lastName != null) {
			return firstName + " " + lastName;
		} else if (firstName != null) {
			return firstName;
		} else if (lastName != null) {
			return lastName;
		}
		return "LinkedIn User";
	}

	@Override
	public String getEmail() {
		return (String) attributes.get("email");
	}

	@Override
	public String getImageUrl() {
		return (String) attributes.get("picture");
	}

	private String getFirstName() {
		// Try processed field first
		String firstName = (String) attributes.get("given_name");
		if (firstName != null) {
			return firstName;
		}

		firstName = (String) attributes.get("first_name");
		if (firstName != null) {
			return firstName;
		}

		// Try raw LinkedIn API format
		Map<String, Object> localizedFirstName = (Map<String, Object>) attributes.get("localizedFirstName");
		if (localizedFirstName != null) {
			return extractLocalizedText(localizedFirstName);
		}
		return null;
	}

	private String getLastName() {
		// Try processed field first
		String lastName = (String) attributes.get("family_name");
		if (lastName != null) {
			return lastName;
		}

		lastName = (String) attributes.get("last_name");
		if (lastName != null) {
			return lastName;
		}

		// Try raw LinkedIn API format
		Map<String, Object> localizedLastName = (Map<String, Object>) attributes.get("localizedLastName");
		if (localizedLastName != null) {
			return extractLocalizedText(localizedLastName);
		}
		return null;
	}

	/**
	 * Extract localized text from LinkedIn's localized format
	 */
	private String extractLocalizedText(Map<String, Object> localizedMap) {
		if (localizedMap == null || localizedMap.isEmpty()) {
			return null;
		}

		// Try common locales first
		String[] preferredLocales = { "en_US", "en", "en_GB" };
		for (String locale : preferredLocales) {
			if (localizedMap.containsKey(locale)) {
				return (String) localizedMap.get(locale);
			}
		}

		// If no preferred locale, return the first available value
		return (String) localizedMap.values().iterator().next();
	}

	@Override
	public String getLocale() {
		return "en_US";
	}
}