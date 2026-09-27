package com.flashdeal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FlashDealApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlashDealApplication.class, args);
    }
}
