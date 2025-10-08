package com.ndb.auction.security;

import java.util.Arrays;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.DefaultAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.AuthenticatedPrincipalOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.ndb.auction.config.LinkedInOAuth2Config;
import com.ndb.auction.security.jwt.AuthEntryPointJwt;
import com.ndb.auction.security.jwt.AuthTokenFilter;
import com.ndb.auction.security.oauth2.CustomAccessTokenResponseConverter;
import com.ndb.auction.security.oauth2.HttpCookieOAuth2AuthorizationRequestRepository;
import com.ndb.auction.security.oauth2.OAuth2AuthenticationFailureHandler;
import com.ndb.auction.security.oauth2.OAuth2AuthenticationSuccessHandler;
import com.ndb.auction.service.CustomOAuth2UserService;
import com.ndb.auction.service.user.UserDetailsServiceImpl;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

	@Autowired
	UserDetailsServiceImpl userDetailsService;

	@Autowired
	private AuthEntryPointJwt unauthorizedHandler;

	@Autowired
	private CustomOAuth2UserService customOAuth2UserService;

	@Autowired
	private OAuth2AuthenticationSuccessHandler oAuth2AuthenticationSuccessHandler;

	@Autowired
	private OAuth2AuthenticationFailureHandler oAuth2AuthenticationFailureHandler;

	@Autowired
	private LinkedInOAuth2Config linkedInOAuth2Config;

	@Bean
	public HttpCookieOAuth2AuthorizationRequestRepository cookieAuthorizationRequestRepository() {
		return new HttpCookieOAuth2AuthorizationRequestRepository();
	}

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	public AuthTokenFilter authenticationJwtTokenFilter() {
		return new AuthTokenFilter();
	}

	@Bean
	public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
		return config.getAuthenticationManager();
	}

	@Bean
	public AuthenticationProvider authenticationProvider() {
		DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
		authProvider.setUserDetailsService(userDetailsService);
		authProvider.setPasswordEncoder(passwordEncoder());
		return authProvider;
	}

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
		http
				.cors(cors -> cors.configurationSource(corsConfig()))
				.csrf(csrf -> csrf.disable())
				.exceptionHandling(exceptions -> exceptions
						.authenticationEntryPoint(unauthorizedHandler))
				.sessionManagement(session -> session
						.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(authz -> authz
						// Public endpoints - no authentication required
						.requestMatchers("/health").permitAll()
						.requestMatchers("/error").permitAll()
						.requestMatchers("/favicon.ico").permitAll()

						// GraphQL UI tools - public access
						.requestMatchers("/playground").permitAll()
						.requestMatchers("/graphiql/**").permitAll()
						.requestMatchers("/vendor/**").permitAll()

						// Authentication and OAuth2 endpoints
						.requestMatchers("/auth/**", "/oauth2/**").permitAll()

						// Payment and webhook endpoints
						.requestMatchers("/shufti/**").permitAll()
						.requestMatchers("/stripe/**").permitAll()
						.requestMatchers("/paypal/**").permitAll()
						.requestMatchers("/crypto/**").permitAll()
						.requestMatchers("/ipn/**").permitAll()
						.requestMatchers("/nyyupay/**").permitAll()

						// Location and public API endpoints
						.requestMatchers("/api/location").permitAll()
						.requestMatchers("/location").permitAll()
						.requestMatchers("/totalsupply/**").permitAll()
						.requestMatchers("/circulatingsupply/**").permitAll()
						.requestMatchers("/marketcap/**").permitAll()
						.requestMatchers("/ndbcoin/**").permitAll()

						// Social endpoints
						.requestMatchers("/social/discord/**").permitAll()

						// Admin endpoints (consider restricting these in production)
						.requestMatchers("/admin/**").permitAll()

						// WebSocket
						.requestMatchers("/ws/**").permitAll()

						// GraphQL endpoint - permitAll but authentication is handled by JWT filter and
						// @PreAuthorize
						.requestMatchers("/graphql/**").permitAll()
						.requestMatchers("/graphql").permitAll()

						// All other requests require authentication
						.anyRequest().authenticated())
				.oauth2Login(oauth2 -> oauth2
						.authorizationEndpoint(authz -> authz
								.baseUri("/oauth2/authorize")
								.authorizationRequestRepository(cookieAuthorizationRequestRepository()))
						.redirectionEndpoint(redirection -> redirection
								.baseUri("/oauth2/callback/*"))
						.tokenEndpoint(token -> token
								.accessTokenResponseClient(authorizationCodeTokenResponseClient()))
						.userInfoEndpoint(userInfo -> userInfo
								.userService(linkedInOAuth2Config.oauth2UserService())) // Use custom service
						.successHandler(oAuth2AuthenticationSuccessHandler)
						.failureHandler(oAuth2AuthenticationFailureHandler))
				.authenticationProvider(authenticationProvider());

		// Add JWT token filter before UsernamePasswordAuthenticationFilter
		http.addFilterBefore(authenticationJwtTokenFilter(), UsernamePasswordAuthenticationFilter.class);

		return http.build();
	}

	@Bean
	public OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> authorizationCodeTokenResponseClient() {
		OAuth2AccessTokenResponseHttpMessageConverter tokenResponseHttpMessageConverter = new OAuth2AccessTokenResponseHttpMessageConverter();
		tokenResponseHttpMessageConverter.setAccessTokenResponseConverter(new CustomAccessTokenResponseConverter());

		RestTemplate restTemplate = new RestTemplate(
				Arrays.asList(new FormHttpMessageConverter(), tokenResponseHttpMessageConverter));
		restTemplate.setErrorHandler(new OAuth2ErrorResponseErrorHandler());

		DefaultAuthorizationCodeTokenResponseClient tokenResponseClient = new DefaultAuthorizationCodeTokenResponseClient();
		tokenResponseClient.setRestOperations(restTemplate);

		return tokenResponseClient;
	}

	@Bean
	public CorsConfigurationSource corsConfig() {
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		CorsConfiguration config = new CorsConfiguration();

		config.setAllowCredentials(true);
		config.addAllowedOriginPattern("*");
		config.addAllowedHeader("*");
		config.addAllowedMethod("OPTIONS");
		config.addAllowedMethod("GET");
		config.addAllowedMethod("POST");
		config.addAllowedMethod("PUT");
		config.addAllowedMethod("DELETE");
		config.addExposedHeader("Authorization");

		source.registerCorsConfiguration("/**", config);
		return source;
	}

	@Bean
	public OAuth2AuthorizedClientService authorizedClientService(
			ClientRegistrationRepository clientRegistrationRepository) {
		return new InMemoryOAuth2AuthorizedClientService(clientRegistrationRepository);
	}

	@Bean
	public OAuth2AuthorizedClientRepository authorizedClientRepository(
			OAuth2AuthorizedClientService authorizedClientService) {
		return new AuthenticatedPrincipalOAuth2AuthorizedClientRepository(authorizedClientService);
	}
}