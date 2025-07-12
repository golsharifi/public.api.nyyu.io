package com.ndb.auction.config;

import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PostConstruct;

@Configuration
@Slf4j
public class AzureBlobConfig {

    @Value("${azure.storage.connection-string}")
    private String connectionString;

    @PostConstruct
    public void init() {
        log.info("Azure Storage Configuration:");
        log.info("Connection String: {}", connectionString != null ? "Configured" : "Not Configured");
    }

    @Bean
    public BlobServiceClient blobServiceClient() {
        try {
            if (connectionString == null || connectionString.trim().isEmpty()) {
                throw new IllegalStateException("Azure Storage connection string is not configured");
            }

            // Log the connection string pattern (without sensitive data)
            log.debug(
                    "Creating BlobServiceClient with connection string pattern: DefaultEndpointsProtocol=***;AccountName=***;AccountKey=***;EndpointSuffix=***");

            return new BlobServiceClientBuilder()
                    .connectionString(connectionString)
                    .buildClient();
        } catch (Exception e) {
            log.error("Failed to create Azure Blob Service Client: {}", e.getMessage());
            throw new IllegalStateException("Failed to initialize Azure Blob Storage client", e);
        }
    }
}