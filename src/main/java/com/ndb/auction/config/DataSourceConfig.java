package com.ndb.auction.config;

import oracle.ucp.jdbc.PoolDataSource;
import oracle.ucp.jdbc.PoolDataSourceFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.sql.SQLException;

@Configuration
public class DataSourceConfig {

    @Value("${spring.datasource.url}")
    private String url;

    @Value("${spring.datasource.username}")
    private String username;

    @Value("${spring.datasource.password}")
    private String password;

    @Value("${spring.datasource.ucp.min-pool-size:2}")
    private int minPoolSize;

    @Value("${spring.datasource.ucp.max-pool-size:100}")
    private int maxPoolSize;

    @Value("${spring.datasource.ucp.initial-pool-size:5}")
    private int initialPoolSize;

    @Value("${spring.datasource.ucp.connection-validation-timeout:30}")
    private int connectionValidationTimeout;

    @Value("${spring.datasource.ucp.inactive-connection-timeout:60}")
    private int inactiveConnectionTimeout;

    @Value("${spring.datasource.ucp.time-to-live-connection-timeout:300}")
    private int timeToLiveConnectionTimeout;

    @Value("${spring.datasource.ucp.abandon-connection-timeout:60}")
    private int abandonConnectionTimeout;

    @Value("${spring.datasource.ucp.connection-wait-timeout:30}")
    private int connectionWaitTimeout;

    @Value("${spring.datasource.ucp.validate-connection-on-borrow:true}")
    private boolean validateConnectionOnBorrow;

    @Bean
    @Primary
    public DataSource dataSource() throws SQLException {
        PoolDataSource dataSource = PoolDataSourceFactory.getPoolDataSource();

        // Basic connection properties
        dataSource.setConnectionFactoryClassName("oracle.jdbc.pool.OracleDataSource");
        dataSource.setURL(url);
        dataSource.setUser(username);
        dataSource.setPassword(password);

        // Connection pool properties
        dataSource.setMinPoolSize(minPoolSize);
        dataSource.setMaxPoolSize(maxPoolSize);
        dataSource.setInitialPoolSize(initialPoolSize);

        // Timeout properties
        dataSource.setConnectionValidationTimeout(connectionValidationTimeout);
        dataSource.setInactiveConnectionTimeout(inactiveConnectionTimeout);
        dataSource.setTimeToLiveConnectionTimeout(timeToLiveConnectionTimeout);
        dataSource.setAbandonedConnectionTimeout(abandonConnectionTimeout);
        dataSource.setConnectionWaitTimeout(connectionWaitTimeout);

        // Validation properties
        dataSource.setValidateConnectionOnBorrow(validateConnectionOnBorrow);
        dataSource.setSQLForValidateConnection("SELECT 1 FROM DUAL");

        // Additional recommended settings
        dataSource.setConnectionPoolName("NyyuAuctionUCP");
        dataSource.setFastConnectionFailoverEnabled(true);

        return dataSource;
    }
}