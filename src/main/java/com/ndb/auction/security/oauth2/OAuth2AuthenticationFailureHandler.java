package com.ndb.auction.security.oauth2;

import com.ndb.auction.exceptions.ProviderMismatchException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.beans.factory.annotation.Value;

import lombok.extern.slf4j.Slf4j;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Slf4j
@Component
public class OAuth2AuthenticationFailureHandler extends SimpleUrlAuthenticationFailureHandler {

        @Value("${frontend.base.url}")
        private String frontendBaseUrl;

        @Autowired
        HttpCookieOAuth2AuthorizationRequestRepository httpCookieOAuth2AuthorizationRequestRepository;

        @Override
        public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                        AuthenticationException exception) throws IOException, ServletException {
                String targetUrl = frontendBaseUrl;

                log.info("onAuthenticationFailure!!! {}", exception.getLocalizedMessage());

                String errorType;
                String errorMessage;
                String errorData = "";

                if (exception instanceof ProviderMismatchException) {
                        ProviderMismatchException providerException = (ProviderMismatchException) exception;
                        errorType = "PROVIDER_MISMATCH";
                        errorMessage = String.format(
                                        "This email is already registered with %s. Please use %s to login.",
                                        capitalizeProvider(providerException.getOriginalProvider()),
                                        capitalizeProvider(providerException.getOriginalProvider()));
                        errorData = providerException.getOriginalProvider();
                } else {
                        errorType = "OAUTH_ERROR";
                        errorMessage = exception.getLocalizedMessage();
                }

                // URL encode the error message to handle special characters
                String encodedMessage = URLEncoder.encode(errorMessage, StandardCharsets.UTF_8);
                String encodedData = URLEncoder.encode(errorData, StandardCharsets.UTF_8);

                // Redirect to frontend signin page instead of backend error page
                if (errorType.equals("PROVIDER_MISMATCH")) {
                        targetUrl = UriComponentsBuilder.fromUriString(targetUrl + "/app/signin")
                                        .queryParam("error", "provider_mismatch")
                                        .queryParam("message", encodedMessage)
                                        .queryParam("provider", encodedData)
                                        .build().toUriString();
                } else {
                        targetUrl = UriComponentsBuilder.fromUriString(targetUrl + "/app/signin")
                                        .queryParam("error", encodedMessage)
                                        .build().toUriString();
                }

                httpCookieOAuth2AuthorizationRequestRepository.removeAuthorizationRequestCookies(request, response);

                getRedirectStrategy().sendRedirect(request, response, targetUrl);
        }

        private String capitalizeProvider(String provider) {
                if (provider == null || provider.isEmpty()) {
                        return provider;
                }
                return provider.substring(0, 1).toUpperCase() + provider.substring(1).toLowerCase();
        }
}