package com.demo.orderservice.service;

import com.demo.orderservice.model.Order;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final String[] PRODUCT_TYPES = {"electronics", "clothing", "food"};

    private final MeterRegistry registry;
    private final Timer processingTimer;
    private final RestTemplate restTemplate;
    private final AtomicInteger pendingCount = new AtomicInteger(0);
    private final Random random = new Random();

    @Value("${services.inventory-url}")
    private String inventoryUrl;

    @Value("${services.notification-url}")
    private String notificationUrl;

    public OrderService(MeterRegistry registry, Timer orderProcessingTimer) {
        this.registry = registry;
        this.processingTimer = orderProcessingTimer;
        this.restTemplate = new RestTemplate();

        Gauge.builder("orders.pending.count", pendingCount, AtomicInteger::get)
                .description("Number of orders currently pending processing")
                .register(registry);

        // background thread simulating queue fluctuation
        Thread.ofVirtual().start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                pendingCount.set(random.nextInt(51));
                try { Thread.sleep(3000); } catch (InterruptedException e) { break; }
            }
        });
    }

    public Order processOrder(String productType, int quantity) {
        return processingTimer.record(() -> {
            Order order = new Order(productType, quantity);
            boolean success = false;
            try {
                boolean inventoryOk = checkInventory(productType, quantity);
                if (inventoryOk) {
                    sendNotification(order.getId(), productType);
                    order.setStatus("COMPLETED");
                    success = true;
                } else {
                    order.setStatus("REJECTED");
                }
            } catch (Exception e) {
                log.warn("Order processing failed: {}", e.getMessage());
                order.setStatus("FAILED");
            }

            Counter.builder("orders.created.total")
                    .tag("status", success ? "success" : "failed")
                    .tag("product_type", productType)
                    .description("Total number of orders created")
                    .register(registry)
                    .increment();

            return order;
        });
    }

    private boolean checkInventory(String productType, int quantity) {
        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    inventoryUrl + "/inventory/check",
                    Map.of("productType", productType, "quantity", quantity),
                    Map.class
            );
            return response.getStatusCode().is2xxSuccessful() &&
                   Boolean.TRUE.equals(response.getBody() != null ? response.getBody().get("available") : false);
        } catch (RestClientException e) {
            log.warn("Inventory service unreachable, assuming available: {}", e.getMessage());
            return true;
        }
    }

    private void sendNotification(String orderId, String productType) {
        try {
            restTemplate.postForEntity(
                    notificationUrl + "/notifications/send",
                    Map.of("orderId", orderId, "type", "ORDER_CREATED", "productType", productType),
                    Map.class
            );
        } catch (RestClientException e) {
            log.warn("Notification service unreachable: {}", e.getMessage());
        }
    }

    public Map<String, Object> getStats() {
        return Map.of(
                "pendingOrders", pendingCount.get(),
                "productTypes", PRODUCT_TYPES
        );
    }
}
