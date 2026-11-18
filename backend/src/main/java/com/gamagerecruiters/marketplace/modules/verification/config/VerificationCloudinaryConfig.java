package com.gamagerecruiters.marketplace.modules.verification.config;

import java.util.HashMap;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import com.cloudinary.Cloudinary;

import io.github.cdimascio.dotenv.Dotenv;

@Configuration
public class VerificationCloudinaryConfig {

    private final Environment environment;

    public VerificationCloudinaryConfig(Environment environment) {
        this.environment = environment;
    }

    @Bean
    public Cloudinary verificationCloudinary() {
        Map<String, String> config = new HashMap<>();

        config.put(
                "cloud_name",
                resolveValue("CLOUDINARY_CLOUD_NAME")
        );

        config.put(
                "api_key",
                resolveValue("CLOUDINARY_API_KEY")
        );

        config.put(
                "api_secret",
                resolveValue("CLOUDINARY_API_SECRET")
        );

        config.put("secure", "true");

        return new Cloudinary(config);
    }

    private String resolveValue(String key) {
        /*
         * First prefer an actual environment variable.
         * This supports deployed environments and terminals
         * where backend/.env has already been loaded.
         */
        String value = environment.getProperty(key);

        if (hasText(value)) {
            return value.trim();
        }

        /*
         * If the backend is started while the working directory
         * is backend/, dotenv-java can find .env directly.
         */
        Dotenv currentDirectory = Dotenv.configure()
                .ignoreIfMissing()
                .ignoreIfMalformed()
                .load();

        value = currentDirectory.get(key);

        if (hasText(value)) {
            return value.trim();
        }

        /*
         * When the application is started from the repository
         * root, backend/.env is used instead.
         */
        Dotenv backendDirectory = Dotenv.configure()
                .directory("backend")
                .ignoreIfMissing()
                .ignoreIfMalformed()
                .load();

        value = backendDirectory.get(key);

        return hasText(value)
                ? value.trim()
                : "";
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}