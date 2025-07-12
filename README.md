# NYYU API - Source Code for AI Knowledge Base

This repository contains the Java Spring Boot source code for the New York Youth University API.

**Purpose**: This is a code-only repository designed for AI assistants to understand the codebase structure and provide coding assistance.

## Project Structure

```
src/
├── main/
│   ├── java/               # Main Java source code
│   └── resources/
│       ├── graphqls/       # GraphQL schema definitions
│       ├── messages/       # Message templates and i18n
│       ├── META-INF/       # Spring Boot metadata
│       ├── playground/     # GraphQL playground resources
│       ├── templates/      # Template files (Thymeleaf, etc.)
│       ├── application-dev.properties     # Development configuration
│       ├── application-prod.properties    # Production configuration template
│       └── application-testnet.properties # Testnet configuration
└── test/
    └── java/               # Test source code
```

## Technology Stack

- **Framework**: Spring Boot
- **Language**: Java
- **Build Tool**: Maven/Gradle
- **Database**: PostgreSQL (production), H2 (development)
- **GraphQL**: Spring GraphQL integration
- **Templates**: Thymeleaf or similar templating engine

## Key Components

- **Controllers**: REST API endpoints and GraphQL resolvers
- **Services**: Business logic layer
- **Repositories**: Data access layer
- **Models/Entities**: JPA entities and DTOs
- **Configuration**: Spring configuration classes
- **Security**: Authentication and authorization
- **GraphQL**: Schema definitions and resolvers
- **Templates**: UI templates and message templates

## GraphQL Integration

The `/graphqls` directory contains GraphQL schema definitions that define the API structure. The playground resources provide interactive GraphQL exploration tools.

## Configuration Files

- **application-dev.properties**: Development environment settings
- **application-prod.properties**: Production configuration template
- **application-testnet.properties**: Test network configuration
- **application.properties**: Base configuration template

These configuration files may contain template values and environment variable references for security.

## Message Templates

The `/messages` directory contains internationalization files and message templates used throughout the application.

## For AI Assistants

This codebase follows standard Spring Boot conventions with GraphQL integration:
- RESTful API design alongside GraphQL
- Service-Repository pattern
- JPA for database operations
- JWT for authentication
- Standard Spring Security configuration
- GraphQL schema-first approach
- Template-based rendering

When providing coding assistance, consider:
- Spring Boot best practices
- GraphQL schema design principles
- RESTful API design principles
- Proper error handling
- Security considerations
- Testing patterns
- Message template management
- Multi-environment configuration
