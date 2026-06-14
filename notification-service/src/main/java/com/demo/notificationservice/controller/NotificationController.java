package com.demo.notificationservice.controller;

import com.demo.notificationservice.service.NotificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @PostMapping("/send")
    public ResponseEntity<Map<String, Object>> sendNotification(@RequestBody Map<String, Object> request) {
        Map<String, String> notification = Map.of(
                "orderId", String.valueOf(request.getOrDefault("orderId", "unknown")),
                "type", String.valueOf(request.getOrDefault("type", "GENERIC")),
                "productType", String.valueOf(request.getOrDefault("productType", "unknown"))
        );

        boolean enqueued = notificationService.enqueue(notification);
        if (enqueued) {
            return ResponseEntity.accepted().body(Map.of(
                    "status", "queued",
                    "queueDepth", notificationService.getQueueDepth()
            ));
        } else {
            return ResponseEntity.status(503).body(Map.of(
                    "status", "rejected",
                    "reason", "queue full"
            ));
        }
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(Map.of(
                "queueDepth", notificationService.getQueueDepth()
        ));
    }
}
