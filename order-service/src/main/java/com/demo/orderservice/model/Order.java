package com.demo.orderservice.model;

import java.time.Instant;
import java.util.UUID;

public class Order {
    private String id;
    private String productType;
    private int quantity;
    private String status;
    private Instant createdAt;

    public Order(String productType, int quantity) {
        this.id = UUID.randomUUID().toString();
        this.productType = productType;
        this.quantity = quantity;
        this.status = "PENDING";
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public String getProductType() { return productType; }
    public int getQuantity() { return quantity; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setStatus(String status) { this.status = status; }
}
