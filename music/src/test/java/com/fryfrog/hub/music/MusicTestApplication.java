package com.fryfrog.hub.music;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {"com.fryfrog.hub.music", "com.fryfrog.hub.common"})
public class MusicTestApplication {
    public static void main(String[] args) {
        SpringApplication.run(MusicTestApplication.class, args);
    }
}
