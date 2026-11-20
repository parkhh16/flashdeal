package com.needsshop.common.config;

import com.needsshop.product.stock.StockStrategyType;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "needsshop")
public record NeedsShopProperties(
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
