package com.ndb.auction;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.MultipartConfigFactory;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.unit.DataSize;

import graphql.kickstart.servlet.apollo.ApolloScalars;
import graphql.schema.GraphQLScalarType;
import jakarta.servlet.MultipartConfigElement;

@SpringBootApplication
@EnableScheduling
public class AuctionApplication {

	public static void main(String[] args) {
		SpringApplication.run(AuctionApplication.class, args);
	}

	@Bean
	public GraphQLScalarType uploadScalarType() {
		return ApolloScalars.Upload;
	}

	@Bean
	public MultipartConfigElement multipartConfigElement() {
		MultipartConfigFactory factory = new MultipartConfigFactory();
		factory.setMaxFileSize(DataSize.ofMegabytes(10));
		factory.setMaxRequestSize(DataSize.ofMegabytes(10));
		return factory.createMultipartConfig();
	}

	@Bean
	public ServletWebServerFactory servletWebServerFactory() {
		return new TomcatServletWebServerFactory();
	}
}