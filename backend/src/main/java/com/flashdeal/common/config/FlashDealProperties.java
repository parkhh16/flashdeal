package com.flashdeal.common.config;

import com.flashdeal.product.stock.StockStrategyType;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "flashdeal")
public record FlashDealProperties(
        Jwt jwt,
        Stock stock,
        Order order,
        Pg pg,
        Payment payment,
        Ai ai
) {
    public record Jwt(String secret, Duration expiration) {
    }

    public record Stock(StockStrategyType strategy) {
    }

    public record Order(Duration reservationTtl) {
    }

    public record Pg(String baseUrl, Duration connectTimeout, Duration readTimeout, int maxAttempts) {
    }

    public record Payment(Duration reconcileGrace) {
    }

    public record Ai(String apiKey, String model, Duration timeout) {
    }
}
