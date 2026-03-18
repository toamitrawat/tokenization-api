package com.example.tokenization;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TokenizationApplication {
    public static void main(String[] args) {
        SpringApplication.run(TokenizationApplication.class, args);
    }
}
