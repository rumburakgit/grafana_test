import random
import time
import requests
import logging

logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s %(message)s')
log = logging.getLogger(__name__)

ORDER_SERVICE_URL = "http://order-service:8080"
PRODUCT_TYPES = ["electronics", "clothing", "food"]

# Traffic scenario weights: 70% success path, 15% errors, 15% edge cases
SCENARIOS = [
    ("normal", 70),
    ("error", 15),
    ("edge", 15),
]

def pick_scenario():
    r = random.randint(1, 100)
    cumulative = 0
    for name, weight in SCENARIOS:
        cumulative += weight
        if r <= cumulative:
            return name
    return "normal"

def send_normal_order():
    product = random.choice(PRODUCT_TYPES)
    qty = random.randint(1, 10)
    return requests.post(
        f"{ORDER_SERVICE_URL}/orders",
        json={"productType": product, "quantity": qty},
        timeout=10
    )

def send_error_order():
    # Invalid product type to trigger bad request
    return requests.post(
        f"{ORDER_SERVICE_URL}/orders",
        json={"productType": "invalid_type", "quantity": 1},
        timeout=10
    )

def send_edge_order():
    # Large quantity to trigger unavailable inventory
    product = random.choice(PRODUCT_TYPES)
    return requests.post(
        f"{ORDER_SERVICE_URL}/orders",
        json={"productType": product, "quantity": 999},
        timeout=10
    )

def run():
    log.info("Load generator starting, targeting %s", ORDER_SERVICE_URL)
    request_count = 0
    error_count = 0

    while True:
        scenario = pick_scenario()
        try:
            if scenario == "normal":
                resp = send_normal_order()
            elif scenario == "error":
                resp = send_error_order()
            else:
                resp = send_edge_order()

            request_count += 1
            status = resp.status_code
            if status >= 400:
                error_count += 1

            if request_count % 20 == 0:
                error_rate = (error_count / request_count) * 100
                log.info("Stats: %d requests, %.1f%% errors", request_count, error_rate)
            else:
                log.debug("Scenario=%s status=%d", scenario, status)

        except requests.exceptions.ConnectionError:
            log.warning("order-service unreachable, retrying in 5s...")
            time.sleep(5)
            continue
        except requests.exceptions.Timeout:
            log.warning("Request timed out")
            error_count += 1
        except Exception as e:
            log.error("Unexpected error: %s", e)

        # 1-3 seconds between requests = ~0.5-1 req/s per instance
        sleep_time = random.uniform(1.0, 3.0)
        time.sleep(sleep_time)

if __name__ == "__main__":
    # wait for services to start
    time.sleep(20)
    run()
