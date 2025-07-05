package com.ndb.auction.security.oauth2.user;

import java.util.Map;

public class TwitterOAuth2UserInfo extends OAuth2UserInfo {
    public TwitterOAuth2UserInfo(Map<String, Object> attributes) {
        super(attributes);
    }

    @Override
    public String getId() {
        return (String) attributes.get("id");
    }

    @Override
    public String getName() {
        String name = (String) attributes.get("name");
        if (name == null || name.isEmpty()) {
            name = (String) attributes.get("username");
        }
        return name;
    }

    @Override
    public String getEmail() {
        // Twitter Free tier might not provide email
        String email = (String) attributes.get("email");
        if (email == null || email.isEmpty()) {
            // Use username as fallback for Free tier
            String username = (String) attributes.get("username");
            return username != null ? username + "@twitter.placeholder" : null;
        }
        return email;
    }

    @Override
    public String getImageUrl() {
        return (String) attributes.get("profile_image_url");
    }

    @Override
    public String getLocale() {
        return (String) attributes.get("location");
    }
}