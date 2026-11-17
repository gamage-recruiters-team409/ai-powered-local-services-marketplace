
package com.gamagerecruiters.marketplace.modules.verification.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;

import org.springframework.transaction.annotation.EnableTransactionManagement;

@Configuration
@EnableTransactionManagement
@Profile("!test")
public class MongoTransactionConfig {

    @Bean
    public MongoTransactionManager transactionManager(
            MongoDatabaseFactory mongoDatabaseFactory
    ) {
        return new MongoTransactionManager(
                mongoDatabaseFactory
        );
    }
}
