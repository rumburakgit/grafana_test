package com.demo.inventoryservice.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    private final MeterRegistry registry;
    private final DistributionSummary responseSummary;
    private final AtomicInteger stockLevel = new AtomicInteger(75);
    private final Random random = new Random();

    public InventoryService(MeterRegistry registry) {
        this.registry = registry;

        this.responseSummary = DistributionSummary.builder("inventory.response.time")
                .description("Inventory check response time in seconds")
                .serviceLevelObjectives(0.05, 0.1, 0.25, 0.5, 1.0)
                .publishPercentileHistogram()
                .register(registry);

        Gauge.builder("inventory.stock.level", stockLevel, AtomicInteger::get)
                .description("Current simulated stock level (0-100)")
                .register(registry);

        // background thread simulating stock level changes
        Thread.ofVirtual().start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                int current = stockLevel.get();
                // gradually drain stock with occasional restocks
                if (random.nextInt(10) < 2) {
                    stockLevel.set(Math.min(100, current + random.nextInt(30)));
                } else {
                    stockLevel.set(Math.max(0, current - random.nextInt(5)));
                }
                try { Thread.sleep(5000); } catch (InterruptedException e) { break; }
            }
        });
    }

    public InventoryResult checkInventory(String productType, int quantity) {
        long startNs = System.nanoTime();

        // simulate random latency 10-800ms
        int delayMs = 10 + random.nextInt(791);
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        double responseTimeSec = (System.nanoTime() - startNs) / 1_000_000_000.0;
        responseSummary.record(responseTimeSec);

        // ~15% random errors
        if (random.nextInt(100) < 15) {
            Counter.builder("inventory.checks.total")
                    .tag("result", "error")
                    .description("Total inventory checks")
                    .register(registry)
                    .increment();
            throw new RuntimeException("Inventory service internal error (simulated)");
        }

        int stock = stockLevel.get();
        boolean available = stock > quantity;
        String result = available ? "available" : "unavailable";

        Counter.builder("inventory.checks.total")
                .tag("result", result)
                .description("Total inventory checks")
                .register(registry)
                .increment();

        log.debug("Inventory check for {} x{}: {} (stock={}, delay={}ms)", productType, quantity, result, stock, delayMs);
        return new InventoryResult(available, stock);
    }

    public record InventoryResult(boolean available, int stockLevel) {}
}
