package com.demo.orderservice.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.DistributionSummary;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class MetricsConfig {

    @Bean
    public Timer orderProcessingTimer(MeterRegistry registry) {
        return Timer.builder("orders.processing.duration")
                .description("Time taken to process an order")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .sla(Duration.ofMillis(100), Duration.ofMillis(250), Duration.ofMillis(500), Duration.ofMillis(1000))
                .register(registry);
    }
}
