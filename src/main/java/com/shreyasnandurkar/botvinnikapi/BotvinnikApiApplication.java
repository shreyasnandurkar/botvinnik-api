package com.shreyasnandurkar.botvinnikapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BotvinnikApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(BotvinnikApiApplication.class, args);
    }

}