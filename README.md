# Order Processing Simulator — Monitoring z Prometheus & Grafana

Demo środowisko do nauki monitoringu: 3 mikroserwisy Spring Boot generujące realistyczne metryki,
automatyczny load generator, Prometheus + Grafana + Alertmanager.

---

## 1. Quick Start — Docker Compose

```bash
# 1. Zbuduj i uruchom wszystko
docker compose up --build -d

# 2. Sprawdź że serwisy wstają (zajmuje ~2-3 min)
docker compose ps

# 3. Otwórz Grafanę
open http://localhost:3000   # login: admin / admin123
```

> **Uwaga:** pierwsze `--build` pobiera obrazy i kompiluje Java (~5-10 min).
> Kolejne uruchomienia: `docker compose up -d` (sekundy).

---

## 2. Sprawdzenie że działa

### Adresy

| Serwis          | URL                                   |
|-----------------|---------------------------------------|
| Grafana         | http://localhost:3000 (admin/admin123)|
| Prometheus      | http://localhost:9090                 |
| Alertmanager    | http://localhost:9093                 |
| order-service   | http://localhost:8080/orders/stats    |
| inventory-service | http://localhost:8081/actuator/health |
| notification-service | http://localhost:8082/notifications/status |

### Ręczne wywołanie zamówienia

```bash
curl -X POST http://localhost:8080/orders \
  -H "Content-Type: application/json" \
  -d '{"productType": "electronics", "quantity": 2}'
```

### Przykładowe query PromQL (Prometheus → http://localhost:9090)

```promql
# Request rate dla order-service
rate(http_server_requests_seconds_count{job="order-service"}[1m])

# Aktualny poziom magazynu
inventory_stock_level

# Głębokość kolejki powiadomień
notifications_queue_depth

# P95 latency zamówień
histogram_quantile(0.95, rate(orders_processing_duration_seconds_bucket[5m]))

# Error rate per service
sum(rate(http_server_requests_seconds_count{status=~"5.."}[2m])) by (job)
/ sum(rate(http_server_requests_seconds_count[2m])) by (job)
```

---

## 3. Minikube Setup (Faza 2)

### Wymagania
- minikube ≥ 1.33
- kubectl
- Docker

### Krok po kroku

```bash
# 1. Uruchom minikube z wystarczającymi zasobami
minikube start --cpus=4 --memory=6144 --driver=docker

# 2. Włącz addony
minikube addons enable ingress
minikube addons enable metrics-server

# 3. Ustaw Docker context na minikube (żeby obrazy były widoczne)
eval $(minikube docker-env)

# 4. Zbuduj obrazy w kontekście minikube
docker build -t order-service:latest ./order-service
docker build -t inventory-service:latest ./inventory-service
docker build -t notification-service:latest ./notification-service
docker build -t load-generator:latest ./load-generator

# 5. Wdróż namespace i serwisy
kubectl apply -f k8s/namespace.yml
kubectl apply -f k8s/prometheus.yml
kubectl apply -f k8s/grafana.yml
kubectl apply -f k8s/order-service.yml
kubectl apply -f k8s/inventory-service.yml
kubectl apply -f k8s/notification-service.yml
kubectl apply -f k8s/load-generator.yml
kubectl apply -f k8s/ingress.yml

# 6. Poczekaj aż pody będą Ready
kubectl get pods -n order-processing -w

# 7. Dodaj wpisy do /etc/hosts
echo "$(minikube ip) grafana.local prometheus.local" | sudo tee -a /etc/hosts

# 8. Otwórz Grafanę
open http://grafana.local
```

### Sprawdzenie stanu

```bash
kubectl get all -n order-processing
kubectl logs -n order-processing deployment/order-service -f
kubectl port-forward -n order-processing svc/prometheus 9090:9090
```

### HPA w akcji

```bash
# Obserwuj autoscaling order-service
kubectl get hpa -n order-processing -w

# Wygeneruj obciążenie (w osobnym terminalu)
kubectl scale deployment/load-generator -n order-processing --replicas=5
```

---

## 4. Ćwiczenia PromQL — 5 zadań

# odpytujemy te adresy
http://localhost:9090/targets?search=
http://order-service:8080/actuator/prometheus -> http://localhost:8080/actuator/prometheus

### Zadanie 1 — Rate i aggregation
> Policz ile zamówień na minutę jest tworzonych z podziałem na typ produktu.
> Które produkty są najpopularniejsze?

```promql
# Podpowiedź: użyj rate() + sum by()
sum(rate(orders_created_total[1m])) by (product_type) * 60
```

rozwiązanie
sum(rate(orders_total[1m])) by (product_type) * 60

---

### Zadanie 2 — Error budget
> Oblicz jaki % requestów do inventory-service kończy się błędem (status=error).
> Ustaw próg alertu na 20%.

```promql
# Podpowiedź: podziel rate() błędów przez rate() wszystkich requestów
sum(rate(inventory_checks_total{result="error"}[5m]))
/
sum(rate(inventory_checks_total[5m]))
```

sum(rate(inventory_checks_total{result="error"}[5m]))
/
sum(rate(inventory_checks_total[5m]))

---

### Zadanie 3 — Latency percentile
> Porównaj P50, P95 i P99 latency dla order-service.
> Kiedy P99 > 2x P50 — co to mówi o rozkładzie latency?

```promql
histogram_quantile(0.50, rate(orders_processing_duration_seconds_bucket[5m]))
or
histogram_quantile(0.95, rate(orders_processing_duration_seconds_bucket[5m]))
or
histogram_quantile(0.99, rate(orders_processing_duration_seconds_bucket[5m]))
```
Wartości są w sekundach (to jednostka orders_processing_duration_seconds). Czyli:

P50 ≈ 0.4s — połowa zamówień procesuje się w 400ms lub szybciej
P95 ≈ 0.7s — 95% zamówień mieści się w 700ms
P99 ≈ 0.8s — 99% zamówień mieści się w 800ms


---

### Zadanie 4 — Predict (predict_linear)
> Przewidź za ile minut stock level spadnie do 0, jeśli obecny trend utrzyma się.

```promql
# Podpowiedź: predict_linear() prognozuje wartość za N sekund
predict_linear(inventory_stock_level[10m], 3600)

Co to robi — patrzy na trend z ostatnich 10 minut i przewiduje jaka będzie wartość za 3600 sekund (1 godzina). Jeśli wynik jest ujemny — oznacza że według trendu magazyn już by był pusty.


inventory_stock_level / -deriv(inventory_stock_level[5m])
Co to robi — deriv() oblicza tempo zmiany (ile jednostek na sekundę spada stock). Dzielisz aktualny poziom przez to tempo — wynik to liczba sekund do zera. Podziel przez 60 żeby dostać minuty.

---

### Zadanie 5 — Złożony alert (multi-condition)
> Napisz query które zwraca 1 gdy JEDNOCZEŚNIE:
> - error rate > 5% na order-service
> - P95 latency > 300ms
> Taki alert byłby bardziej precyzyjny niż oddzielne alerty.

```promql
(
  sum(rate(http_server_requests_seconds_count{job="order-service", status=~"5.."}[2m]))
  / sum(rate(http_server_requests_seconds_count{job="order-service"}[2m]))
  > 0.05
)
and
(
  histogram_quantile(0.95, rate(orders_processing_duration_seconds_bucket[5m]))
  > 0.3
)
```

---

## Architektura

```
load-generator
     │ POST /orders (co 1-3s)
     ▼
order-service :8080
  ├── POST /inventory/check ──► inventory-service :8081
  │                              └── ~15% błędów, 10-800ms latency
  └── POST /notifications/send ► notification-service :8082
                                  └── kolejka max 100, batch processing co 5s

Prometheus :9090 ◄── scrape /actuator/prometheus (co 15s) ──┬── order-service
Grafana :3000   ──► Prometheus                              ├── inventory-service
Alertmanager :9093 ◄── Prometheus alerts                    └── notification-service
```

## Metryki custom

| Metryka | Typ | Serwis | Tagi |
|---------|-----|--------|------|
| `orders_created_total` | Counter | order | status, product_type |
| `orders_processing_duration_seconds` | Timer | order | — |
| `orders_pending_count` | Gauge | order | — |
| `inventory_checks_total` | Counter | inventory | result |
| `inventory_response_time` | Histogram | inventory | — |
| `inventory_stock_level` | Gauge | inventory | — |
| `notifications_queue_depth` | Gauge | notification | — |
| `notifications_sent_total` | Counter | notification | — |
| `notifications_failed_total` | Counter | notification | — |
| `notifications_batch_processing_duration_seconds` | Timer | notification | — |
