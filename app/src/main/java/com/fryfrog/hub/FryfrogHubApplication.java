package com.fryfrog.hub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class FryfrogHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(FryfrogHubApplication.class, args);
    }
}
