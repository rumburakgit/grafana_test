package com.demo.notificationservice.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final MeterRegistry registry;
    private final Timer batchProcessingTimer;
    private final Counter sentCounter;
    private final Counter failedCounter;
    private final ConcurrentLinkedQueue<Map<String, String>> queue = new ConcurrentLinkedQueue<>();
    private final Random random = new Random();

    @Value("${notification.queue.max-size:100}")
    private int maxQueueSize;

    @Value("${notification.batch.size:10}")
    private int batchSize;

    public NotificationService(MeterRegistry registry) {
        this.registry = registry;

        Gauge.builder("notifications.queue.depth", queue, ConcurrentLinkedQueue::size)
                .description("Current depth of the notification queue")
                .register(registry);

        this.batchProcessingTimer = Timer.builder("notifications.batch.processing.duration")
                .description("Time taken to process a batch of notifications")
                .publishPercentiles(0.5, 0.95)
                .register(registry);

        this.sentCounter = Counter.builder("notifications.sent.total")
                .description("Total notifications successfully sent")
                .register(registry);

        this.failedCounter = Counter.builder("notifications.failed.total")
                .description("Total notifications that failed to send")
                .register(registry);
    }

    public boolean enqueue(Map<String, String> notification) {
        if (queue.size() >= maxQueueSize) {
            log.warn("Notification queue full, dropping notification for order {}", notification.get("orderId"));
            failedCounter.increment();
            return false;
        }
        queue.add(notification);
        return true;
    }

    @Scheduled(fixedDelayString = "${notification.batch.interval-ms:5000}")
    public void processBatch() {
        if (queue.isEmpty()) return;

        batchProcessingTimer.record(() -> {
            List<Map<String, String>> batch = new ArrayList<>();
            for (int i = 0; i < batchSize && !queue.isEmpty(); i++) {
                Map<String, String> item = queue.poll();
                if (item != null) batch.add(item);
            }

            if (batch.isEmpty()) return;

            // simulate processing time
            int delayMs = 50 + random.nextInt(200);
            try { Thread.sleep(delayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

            // ~5% failure rate in batch processing
            long failed = batch.stream().filter(n -> random.nextInt(100) < 5).count();
            long sent = batch.size() - failed;

            sentCounter.increment(sent);
            failedCounter.increment(failed);

            log.info("Batch processed: {} sent, {} failed, queue remaining: {}", sent, failed, queue.size());
        });
    }

    public int getQueueDepth() {
        return queue.size();
    }
}
