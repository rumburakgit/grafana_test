# Order Processing Simulator — Monitoring z Prometheus & Grafana

Demo środowisko do nauki monitoringu: 3 mikroserwisy Spring Boot generujące realistyczne metryki,
automatyczny load generator, Prometheus + Grafana + Alertmanager.

---

## 0. Jak działają serwisy i dlaczego co pewien czas "trwają długo"

Cały system symuluje typowy, niedoskonały sklep internetowy: zamówienie przechodzi przez
3 serwisy, z których każdy ma wbudowaną **sztuczną losowość** — zmienne opóźnienia, losowe
błędy i zmieniający się stan magazynu. Dzięki temu metryki w Grafanie wyglądają jak
"żywy" produkcyjny system, a nie płaska linia.

### order-service (:8080) — orkiestrator

Odbiera `POST /orders {productType, quantity}` i dla każdego zamówienia:

1. Startuje stoper (`orders_processing_duration_seconds`).
2. Woła **inventory-service** (`POST /inventory/check`), żeby sprawdzić dostępność produktu.
3. Jeśli magazyn ma towar (`available=true`) → woła **notification-service**
   (`POST /notifications/send`), a zamówienie dostaje status **COMPLETED**.
4. Jeśli magazynu nie ma (`available=false`) → zamówienie dostaje status **REJECTED**
   (powiadomienie nie jest wysyłane).
5. Zwiększa licznik `orders_created_total{status, product_type}` i zapisuje czas trwania
   całej operacji w `orders_processing_duration_seconds`.

**"Fail-open" — najważniejsza sztuczka:** jeśli inventory-service nie odpowie (timeout,
błąd 5xx, połączenie odrzucone), order-service **nie wywala zamówienia**. Loguje ostrzeżenie
`"Inventory service unreachable, assuming available"` i traktuje produkt jako dostępny,
więc zamówienie i tak kończy się jako COMPLETED. To pokazuje typowy realny kompromis:
"lepiej dowieźć zamówienie niż wywalić cały checkout, bo padł jeden zależny serwis" —
ale też uczy, że takie podejście maskuje błędy w metrykach (error rate inventory-service
rośnie, a order-service "tego nie widać").

Dodatkowo w tle co 3 sekundy aktualizowany jest gauge `orders_pending_count` losową
wartością 0-50 — to czysto kosmetyczna symulacja "głębokości kolejki zamówień", niezwiązana
z realnym przetwarzaniem.

### inventory-service (:8081) — magazyn z losowym opóźnieniem i błędami

Dla każdego `POST /inventory/check`:

- **Losowe opóźnienie 10-800ms** (`Thread.sleep`) przed odpowiedzią — symuluje zmienny
  czas odpytania bazy danych / magazynu. To jest **główny powód, dla którego część
  requestów "trwa długo"** — rozkład czasu odpowiedzi jest szeroki, więc P50 vs P95/P99
  w Grafanie wyraźnie się różnią.
- **~15% requestów kończy się sztucznym błędem 500** (`RuntimeException("Inventory
  service internal error (simulated)")`) — symulacja niestabilnego serwisu. Te requesty
  zliczane są w `inventory_checks_total{result="error"}`. Jak opisano wyżej, order-service
  i tak "ratuje" takie zamówienie (fail-open).
- W pozostałych przypadkach porównuje `quantity` z aktualnym `stock` i zwraca
  `available = stock > quantity`, zliczając `inventory_checks_total{result="available"|"unavailable"}`.
- Każdy request (sukces i błąd) zapisuje realny czas trwania w
  `inventory_response_time` (histogram z SLO 0.05/0.1/0.25/0.5/1.0s).

W tle, **co 5 sekund**, osobny wątek aktualizuje stan magazynu (`inventory_stock_level`,
start = 75, zakres 0-100): z 20% szansą magazyn jest "dostawiany" (+0..29), w pozostałych
przypadkach jest "drenowany" (-0..4). Dzięki temu stock level powoli oscyluje, a czasem
spada na tyle nisko, że zamówienia o większej ilości zaczynają dostawać `unavailable`
i kończą się jako REJECTED — bez żadnej zmiany w kodzie, tylko przez naturalny dryf stanu.

### notification-service (:8082) — asynchroniczna kolejka powiadomień

`POST /notifications/send` **nie wysyła powiadomienia od razu** — tylko wkłada je do
kolejki w pamięci (max 100 elementów) i odpowiada natychmiast:

- kolejka ma miejsce → `202 {"status":"queued"}`, gauge `notifications_queue_depth` rośnie,
- kolejka pełna → `503 {"status":"rejected","reason":"queue full"}` i licznik
  `notifications_failed_total` rośnie (order-service loguje to jako warning, ale **nie**
  zmienia statusu zamówienia — wciąż COMPLETED).

Niezależnie od tego, **co 5 sekund** (`notification.batch.interval-ms`, domyślnie 5000ms)
działa wsadowy processor, który pobiera do 10 elementów z kolejki i dla każdego:

- symuluje przetwarzanie z opóźnieniem **50-250ms** (`Thread.sleep`),
- z ~5% szansą oznacza je jako nieudane (`notifications_failed_total++`), w pozostałych
  przypadkach jako wysłane (`notifications_sent_total++`),
- czas całego batcha zapisuje w `notifications_batch_processing_duration_seconds`.

Czyli "wysyłka maila" widoczna w aplikacji jest natychmiastowa (202/503), ale realne
przetworzenie zachodzi do 5 sekund później, w tle, z własnym (niezależnym) wskaźnikiem
błędów.

### load-generator — kto generuje ruch

Co 1-3 sekundy wysyła `POST /orders` do order-service w trzech wariantach:

- **70% "normal"** — losowy `productType` (electronics/clothing/food), `quantity` 1-10,
- **15% "error"** — `productType="invalid_type"`, `quantity=1` (inventory-service nie
  waliduje typu produktu, więc to trafia do metryk z tagiem `product_type="invalid_type"`,
  ale samo zamówienie nie jest "błędne" technicznie),
- **15% "edge"** — losowy `productType`, `quantity=999` — to zawsze przekracza stock
  (max 100), więc takie zamówienie praktycznie zawsze kończy się jako **REJECTED**
  (`available=false`).

### Przykładowy przebieg jednego zamówienia (krok po kroku)

Załóżmy `POST /orders {"productType": "electronics", "quantity": 2}`:

1. **order-service** przyjmuje request, startuje timer `orders_processing_duration_seconds`
   i otwiera root span tracingu (`POST /orders`).
2. order-service woła `POST http://inventory-service:8081/inventory/check
   {"productType":"electronics","quantity":2}` — powstaje child span tej rozmowy.
3. **inventory-service** śpi losowo 10-800ms (np. 430ms), po czym z 85% szansą liczy
   `available = stock > 2` (np. stock=63 → `available=true`). Zapisuje
   `inventory_response_time≈0.43s` i zwiększa `inventory_checks_total{result="available"}`.
   *(Jeśli trafi się 15% scenariusz błędu: inventory-service zwraca 500, span jest
   oznaczony `error=true`, a order-service łapie wyjątek, loguje "assuming available"
   i kontynuuje, jakby `available=true`.)*
4. Skoro `available=true`, order-service woła
   `POST http://notification-service:8082/notifications/send {"orderId": ...}` —
   kolejny child span.
5. **notification-service** sprawdza miejsce w kolejce (max 100), wkłada wiadomość,
   `notifications_queue_depth` +1, odpowiada `202 {"status":"queued"}` od razu
   (bez sztucznego opóźnienia na tym etapie).
6. order-service ustawia zamówieniu status **COMPLETED**, zwiększa
   `orders_created_total{status="success", product_type="electronics"}`, zamyka timer
   `orders_processing_duration_seconds` (łączny czas ≈ czas inventory-check + drobny
   narzut sieciowy + wywołanie notification) i odpowiada wołającemu JSON-em zamówienia.
   Trace z 3 spanami (order → inventory, order → notification) jest kompletny i widoczny
   w Tempo/Jaeger.
7. **Do 5 sekund później**, niezależnie od kroku 6, notification-service w swoim
   wsadowym processorze odbiera wcześniej zakolejkowaną wiadomość, śpi 50-250ms i z 95%
   szansą oznacza ją jako wysłaną (`notifications_sent_total++`), aktualizując
   `notifications_batch_processing_duration_seconds` i zmniejszając
   `notifications_queue_depth`. Ten krok **nie wpływa** już na status zamówienia
   zwrócony w kroku 6 — jest całkowicie odseparowany.

Gdyby w kroku 3 magazyn miał `stock <= 2` (np. po kilku cyklach "drenowania" co 5s, albo
przy `quantity=999` z load-generatora), zamówienie zatrzymałoby się na statusie
**REJECTED** już po kroku 3 — bez wywołania notification-service.

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
| Jaeger UI       | http://localhost:16686                |
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

## Distributed Tracing

Aplikacje są zinstrumentowane przy użyciu Micrometer Tracing + OpenTelemetry
(auto-konfiguracja — bez zmian w kodzie biznesowym, poza drobną poprawką
wstrzykiwania `RestTemplate` przez `RestTemplateBuilder`, wymaganą żeby
auto-instrumentacja w ogóle mogła podłączyć się pod wywołania HTTP).

Trace'y są wysyłane przez OTLP (gRPC) do **Tempo**, które integruje się
z Grafaną (Service Map, Explore, exemplary). Kontener **Jaeger** też działa
(port 16686) — ale od Fazy 2 nie odbiera nowych trace'ów (apki eksportują
do Tempo, nie do Jaegera). Jeśli chcesz wrócić do samego Jaegera, zmień
`otel.exporter.otlp.endpoint` z `http://tempo:4317` na `http://jaeger:4317`
w `application.yml` każdego serwisu.

### Jak przeglądać trace'y (Grafana + Tempo)

1. Wyślij kilka requestów:
   ```bash
   curl -X POST http://localhost:8080/orders \
     -H "Content-Type: application/json" \
     -d '{"productType":"electronics","quantity":1}'
   ```
2. Wejdź na http://localhost:3000, otwórz dashboard **"Traces Overview"**
   (folder "Order Processing Simulator")
3. Panel **"Service Map"** pokazuje graf połączeń:
   order-service → inventory-service / notification-service
4. Panel **"Request Duration P95"** ma włączone exemplary — kliknij punkt
   na wykresie i wybierz "Query with Tempo", aby przejść do konkretnego
   trace'u
5. Alternatywnie: Grafana → Explore → datasource **Tempo** → wyszukaj po
   `service.name`

### Co obserwować

- Korzeniowy span `http post /orders` w order-service — całkowity czas requestu
- Span wywołania do inventory-service — czas sprawdzenia stanu magazynu
- Span wywołania do notification-service — czas wysłania powiadomienia
- Gdy inventory-service zwraca błąd — span jest oznaczony jako `error=true`
  i zaznaczony na czerwono
- Porównaj trace szybkiego requestu z wolnym — różnica w czasie zwykle
  pochodzi ze span'a inventory-service

> **Uwaga:** Tempo (local storage) i Jaeger all-in-one (in-memory)
> przechowują dane tymczasowo — po `docker compose down -v` trace'y znikają.
> To normalne dla wersji demo.
>
> Sampling jest ustawiony na `1.0` (100% requestów) tylko do nauki —
> na produkcji użyj wartości `0.01`–`0.1`.

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
http://localhost:8080/actuator/prometheus

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
