package com.demo.inventoryservice.controller;

import com.demo.inventoryservice.service.InventoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/inventory")
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @PostMapping("/check")
    public ResponseEntity<Map<String, Object>> checkInventory(@RequestBody Map<String, Object> request) {
        String productType = (String) request.getOrDefault("productType", "unknown");
        int quantity = (int) request.getOrDefault("quantity", 1);

        InventoryService.InventoryResult result = inventoryService.checkInventory(productType, quantity);
        return ResponseEntity.ok(Map.of(
                "available", result.available(),
                "stockLevel", result.stockLevel(),
                "productType", productType
        ));
    }
}
