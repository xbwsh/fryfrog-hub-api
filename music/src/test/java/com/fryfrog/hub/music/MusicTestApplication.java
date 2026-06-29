package com.fryfrog.hub.music;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@ComponentScan(basePackages = {"com.fryfrog.hub.music", "com.fryfrog.hub.common"})
@EntityScan(basePackages = {"com.fryfrog.hub.music.model", "com.fryfrog.hub.common.model"})
@EnableJpaRepositories(basePackages = {"com.fryfrog.hub.music.repository", "com.fryfrog.hub.common.repository"})
public class MusicTestApplication {
    public static void main(String[] args) {
        SpringApplication.run(MusicTestApplication.class, args);
    }
}
