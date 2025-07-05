package com.ndb.auction.controllers;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Controller
@RequestMapping("/oauth2")
public class OAuth2CallbackController {

    @GetMapping("/callback/{registrationId}")
    public void callback(@PathVariable String registrationId, HttpServletRequest request,
            HttpServletResponse response) {
        // This endpoint is handled by Spring Security OAuth2
        // The actual processing happens in OAuth2AuthenticationSuccessHandler
        // This controller just ensures the mapping exists for all providers
    }
}