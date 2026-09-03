package com.fryfrog.hub.common.config;

import com.fryfrog.hub.common.util.SubsonicPasswordEncryptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SubsonicConfig {

    @Bean
    public SubsonicPasswordEncryptor subsonicPasswordEncryptor(
            @Value("${SUBSONIC_ENCRYPT_KEY:}") String key) {
        return new SubsonicPasswordEncryptor(key);
    }
}
