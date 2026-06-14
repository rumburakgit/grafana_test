package com.demo.orderservice.controller;

import com.demo.orderservice.model.Order;
import com.demo.orderservice.service.OrderService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<Order> createOrder(@RequestBody Map<String, Object> request) {
        String productType = (String) request.getOrDefault("productType", "electronics");
        int quantity = (int) request.getOrDefault("quantity", 1);

        if (!java.util.Arrays.asList("electronics", "clothing", "food").contains(productType)) {
            return ResponseEntity.badRequest().build();
        }

        Order order = orderService.processOrder(productType, quantity);

        if ("FAILED".equals(order.getStatus())) {
            return ResponseEntity.internalServerError().body(order);
        }
        return ResponseEntity.ok(order);
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        return ResponseEntity.ok(orderService.getStats());
    }
}
