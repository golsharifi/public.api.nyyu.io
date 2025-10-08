package com.ndb.auction.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import com.ndb.auction.service.CustomOAuth2UserService;

import java.util.HashMap;
import java.util.Map;
import java.util.Collections;

import org.springframework.beans.factory.annotation.Autowired;

@Configuration
public class LinkedInOAuth2Config {

    @Autowired
    private CustomOAuth2UserService customOAuth2UserService;

    @Bean
    public OAuth2UserService<OAuth2UserRequest, OAuth2User> oauth2UserService() {
        return new OAuth2UserService<OAuth2UserRequest, OAuth2User>() {
            @Override
            public OAuth2User loadUser(OAuth2UserRequest userRequest) {
                String registrationId = userRequest.getClientRegistration().getRegistrationId();

                if ("linkedin".equals(registrationId)) {
                    // For LinkedIn, create a minimal user without calling the API
                    return createLinkedInUserWithoutAPI(userRequest);
                } else {
                    // For other providers, use the custom service
                    return customOAuth2UserService.loadUser(userRequest);
                }
            }
        };
    }

    private OAuth2User createLinkedInUserWithoutAPI(OAuth2UserRequest userRequest) {
        // Create minimal attributes for LinkedIn user
        Map<String, Object> attributes = new HashMap<>();

        // Generate a unique ID based on access token for consistency
        String accessToken = userRequest.getAccessToken().getTokenValue();
        String uniqueId = "linkedin_" + Math.abs(accessToken.hashCode());

        attributes.put("id", uniqueId);
        attributes.put("sub", uniqueId);
        attributes.put("name", "LinkedIn User");
        attributes.put("given_name", "LinkedIn");
        attributes.put("family_name", "User");

        // Create fallback email
        String fallbackEmail = uniqueId + "@linkedin.placeholder.com";
        attributes.put("email", fallbackEmail);
        attributes.put("email_verified", false);
        attributes.put("email_requires_verification", true);

        // Process through your custom service to create the user
        try {
            return customOAuth2UserService.processUserDetails("linkedin", attributes);
        } catch (Exception e) {
            // If that fails, return a basic OAuth2User
            return new DefaultOAuth2User(
                    Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")),
                    attributes,
                    "id");
        }
    }
}