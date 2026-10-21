# 01 — Problem Statement

> **Status:** v1.0 — Approved
> **Last updated:** 2026-10-20

---

## 1. Context

- DOMS (Distributed Order Management System) is a backend that lets customers place orders for products with limited stock.
- It must stay correct (**never oversell**) and responsive during traffic spikes such as flash sales.
- It's also a learning project for distributed-systems patterns: sagas, eventual consistency, idempotency, and event-driven communication.

### Why microservices?

- **A monolith could solve this problem.** Horizontal scaling (many copies behind a load balancer) already gives load distribution and survives a single server failure. For a small team, a modular monolith is often the better choice.
- **What we gain by splitting into services:**
  - **Independent scaling.** The product catalog is read-heavy, while inventory and payment are write-heavy with different load profiles.
  - **Independent deployment.** A payment change doesn't require redeploying ordering.
  - **Fault isolation**, when communication is asynchronous. A slow payment provider doesn't block order placement.
  - **Clear ownership.** Each service owns its data and its rules.
- **What we accept in exchange:**
  - Network failures between services
  - No single database transaction across services, so we need **sagas and eventual consistency**
  - More infrastructure (Kafka, multiple databases) and harder debugging
- **Honest motivation:** the primary goal is to learn and demonstrate the patterns distributed systems require.

---

## 2. Actors

- **Customer**: browses products and places orders. This is the only human actor in Phase 1.
- **System processes**: the reservation-expiry job and the payment processor.
- **No admin in Phase 1.** Products and stock are loaded from **seed data** at startup.

### Customer identity (Phase 1)

- No authentication in Phase 1. The customer is identified by an **`X-Customer-Id` request header**.
- **Known risk:** any caller can impersonate any customer. This is acceptable for local development only.
- **Phase 2:** the header is replaced by the subject of a verified JWT. Business code reads the customer ID from one place, so the swap is contained.

---

## 3. Goals (Phase 1)

- G1: A customer can place an order for one or more products.
- G2: Stock is **reserved atomically** per item when the order is placed. Two customers can never get the same last unit.
- G3: If some items are out of stock, the order proceeds with the **available items only** (partial order). See §6.1.
- G4: Payment is processed **asynchronously** by a mock payment service.
- G5: If a reservation isn't paid within the expiry window, it's **released automatically**.
- G6: Retried or duplicate requests **never create duplicate orders** (idempotency).
- G7: A customer can check order status at any time.
- G8: The whole system runs locally with **one command** (Docker Compose).

## 4. Non-goals (out of scope for Phase 1)

- Authentication and authorization (Phase 2, JWT)
- Cart (the order request carries its items directly)
- Notifications such as email or SMS (Phase 2)
- Real payment gateway integration (we use a mock)
- Shipping, delivery tracking, rider ETA, returns
- Customer cancellation after an order is `CONFIRMED`
- Refund *workflows* beyond the automatic mock refund in §7.4
- Multiple sellers, discounts, coupons, taxes
- Per-customer purchase limits across orders (needs real identity, Phase 2)
- Caching layer (Redis). Not justified by Phase 1 numbers; see §8.
- Frontend (API only; tested with Postman or curl)
- Admin interface (seed data instead)

---

## 5. Services (Phase 1)

| Service | Responsibility | Owns data |
|---|---|---|
| `order-service` | Accepts orders, owns the order state machine, enforces idempotency | `orders`, `order_items`, `idempotency_keys` |
| `inventory-service` | Product catalog, prices and stock; atomic reservation, release and expiry | `products`, `inventory`, `reservations` |
| `payment-service` | Mock payment processing; emits payment result events; mock refunds | `payments` |

- Each service owns **its own database schema**. No service reads another service's tables.
- Phase 2 additions: `auth-service`, `notification-service`, and splitting `product-service` out of inventory.

---

## 6. Order lifecycle (state machine)

```
              ≥1 item reserved                      payment success
  [CREATED] ─────────────────────► [PAYMENT_PENDING] ─────────────────► [CONFIRMED]
      │                                   │
      │ 0 items reserved                  │ payment failed / reservation expired / customer cancelled
      ▼                                   ▼
  [REJECTED]                         [CANCELLED]
```

- **Terminal states:** `CONFIRMED`, `REJECTED`, `CANCELLED`. They never change again.
- **Every non-terminal state has a way out.** `PAYMENT_PENDING` is bounded by the reservation expiry.
- **Illegal transitions are rejected**, for example `CANCELLED → CONFIRMED`.
- **Customer cancellation** is allowed only in `PAYMENT_PENDING` in Phase 1.
- `REJECTED` orders are **stored, not discarded**, so we can analyse lost sales such as stockouts during a flash sale.
- Orders are **never deleted**. They're financial records.
- Post-Phase-1 states (`SHIPPED`, `DELIVERED`, `RETURN_REQUESTED`) will follow `CONFIRMED`.

### 6.1 Partial orders

- Each item is reserved **independently**, and each order item has its own status: `RESERVED` or `OUT_OF_STOCK`.
- **At least 1 item reserved** → the order moves to `PAYMENT_PENDING`. The payable amount covers **reserved items only**.
- **0 items reserved** → the order moves to `REJECTED`.
- The `POST /orders` response tells the customer **which items were dropped**, so they can decide whether to pay or cancel.
- On payment failure, expiry or cancellation, **all** reserved items of the order are released.

---

## 7. Key problems & chosen approaches

### 7.1 Overselling (race condition)

- **Problem:** many concurrent requests compete for the last units of the same product. Without care, two orders both succeed for one unit.
- **Approach:** reserve stock **before** payment, using an **atomic conditional update**:
  ```sql
  UPDATE inventory SET available = available - :qty
  WHERE product_id = :id AND available >= :qty;
  -- 0 rows updated => this item is OUT_OF_STOCK
  ```
- **Alternatives considered:**
  - Optimistic locking (`@Version`): rejected. At flash-sale contention it causes a **retry storm**.
  - Pessimistic locking (`SELECT … FOR UPDATE`): acceptable but slower. It holds the lock across a read, an application-side check and a write.
- The stock count shown on the product page is **only a hint**. The database update is the source of truth.

### 7.2 Duplicate orders (idempotency)

- **Problem:** a double-click or network retry sends the same "place order" request twice.
- **Approach:** an **`Idempotency-Key` header** (a UUID generated by the client for each checkout attempt), stored with a **unique constraint**.
- Keyed on the **request**, not the customer. A customer may legitimately place a second, different order.

| Case | Behaviour |
|---|---|
| New key | Process the request and store the key with the response |
| Same key, same body, already completed | Return the **stored original response**. No new order. |
| Same key, **different body** | `422 Unprocessable Entity`: the key was reused for a different request |
| Same key, first request **still in progress** | `409 Conflict`: the client should retry shortly |
| Key retention | **24 hours**, which covers any realistic client retry window |

### 7.3 Abandoned reservations (expiry)

- **Problem:** stock is reserved but the customer never pays, so the stock is locked forever.
- **Approach:** the **expiry job lives in `order-service`**, which owns the state machine. A **scheduled job** (`@Scheduled`, every 30 seconds) finds `PAYMENT_PENDING` orders older than the expiry window and moves them to `CANCELLED` with a conditional update (§7.4). Only if that update wins does it publish `OrderCancelled`, and only then does `inventory-service` release the stock.
- **Why not in `inventory-service`?** Inventory could release stock *after* a payment succeeded but *before* it heard about `OrderConfirmed`, which would oversell. A single owner of the decision removes that race.
- `inventory-service` keeps a **safety-net sweep** with a much longer timeout. It handles orphan reservations, where stock was reserved but the order was never saved (for example, order-service crashed). Details are in the LLD.
- **Expiry window: 5 minutes** (configurable). During a flash sale, long holds take stock away from customers who are ready to pay. 5 minutes is enough to complete a payment.

### 7.4 Late payment vs expiry (race between system processes)

- **Problem:** the expiry job cancels an order, then a payment-success event arrives afterwards.
- **Approach:** **conditional status updates**. Whichever runs first wins; the other updates 0 rows.
  ```sql
  UPDATE orders SET status = 'CONFIRMED' WHERE id = :id AND status = 'PAYMENT_PENDING';
  UPDATE orders SET status = 'CANCELLED' WHERE id = :id AND status = 'PAYMENT_PENDING';
  ```
- If payment loses the race, the money is **refunded automatically** (mocked in Phase 1). This is a **compensating action**, the building block of the **Saga pattern** detailed in the HLD.

### 7.5 Synchronous vs asynchronous

- **Synchronous** (the customer waits): `POST /orders` creates the order and reserves stock per item, then returns `201 { orderId, status, items[], payableAmount }`.
- **Asynchronous** (background, through Kafka): payment processing, order confirmation or cancellation, and refunds.
- The client learns the final status by polling `GET /orders/{id}`.

### 7.6 Price at order time

- `order_items` stores a **snapshot** of `unit_price` taken at reservation time, as returned by `inventory-service`.
- Later price changes never affect existing orders. A past invoice must show what the customer actually paid.
- The client **never sends prices**. The server determines them.

### 7.7 Mock payment behaviour

- The order request carries `paymentMethod`, which makes the outcome **deterministic** for testing:
  - `MOCK_SUCCESS`: the payment succeeds.
  - `MOCK_FAIL`: the payment fails, and the order is `CANCELLED`.
  - `MOCK_NO_RESPONSE`: the payment never completes, so the order is `CANCELLED` by expiry.
- An optional, configurable processing delay simulates real gateway latency.

---

## 8. Scale & non-functional requirements

| Metric | Value |
|---|---|
| Daily visitors | ~30,000 |
| Orders per day | ~5,000 (≈ 0.06 orders/sec average) |
| Flash-sale peak (writes) | **50 orders/sec** for 5 minutes (~800× average) |
| Flash-sale peak (reads) | ~10,000 visitors × 10 product views ÷ 300 s ≈ **~330 reads/sec** |
| Max quantity per item, per order | **5** (configurable), to limit bulk buying by bots |
| `POST /orders` latency | p99 < 500 ms |
| Consistency | **Strong** for inventory (never oversell). **Eventual** for order confirmation. |

- **Design for the peak, not the average.**
- The real bottleneck isn't total writes. It's **lock contention on one hot product row**.
- About 330 reads per second is comfortably handled by indexed Postgres. A **cache isn't justified yet**. Redis will be introduced only when measured load or a specific use case (such as rate limiting or hot-product reads) requires it.

---

## 9. Definition of Done (Phase 1)

- [ ] `docker compose up` starts all 3 services, Postgres and Kafka
- [ ] Happy path: place order → reserved → paid → `CONFIRMED`
- [ ] All items out of stock → `REJECTED`
- [ ] Partial: 3 items with 1 out of stock → `PAYMENT_PENDING` with 2 items; amount covers 2 items
- [ ] `MOCK_FAIL` → `CANCELLED`, stock released
- [ ] `MOCK_NO_RESPONSE` → expiry → `CANCELLED`, stock released
- [ ] Late payment after expiry → order stays `CANCELLED`, refund recorded
- [ ] Same `Idempotency-Key` sent twice → exactly one order
- [ ] Concurrency test: 50 parallel orders for a stock of 1 → exactly 1 reserved
- [ ] Unit and integration tests pass
- [ ] README with an architecture diagram and run instructions

---

## 10. Decision log

| # | Decision | Rationale |
|---|---|---|
| D1 | Microservices (3 services) | Learning goal, plus independent scaling of read-heavy vs write-heavy parts. Costs accepted (see §1). |
| D2 | `X-Customer-Id` header instead of auth | Defers auth to Phase 2. Risk acknowledged. |
| D3 | Partial orders allowed | Better conversion. The customer sees dropped items before paying. |
| D4 | Atomic conditional update for stock | Shortest lock time under hot-row contention |
| D5 | Idempotency key per request, kept for 24 hours | Distinguishes retries from new orders |
| D6 | 5-minute reservation expiry | Balances payment time against stock availability in sales |
| D7 | Conditional status updates | Resolves the expiry-vs-payment race without explicit locks |
| D8 | Price snapshot in `order_items` | Orders are immutable financial records |
| D9 | Deterministic mock payment via `paymentMethod` | Repeatable tests for every failure path |
| D10 | No Redis in Phase 1 | Not justified by estimated load |
| D11 | Expiry job in `order-service`; inventory only releases stock on `OrderCancelled` | One owner of the cancel decision prevents releasing stock for a paid order |

---

## 11. Glossary

- **Race condition:** concurrent operations on shared data, where the outcome depends on their timing.
- **Idempotency:** performing an operation once or many times leaves the system in the same state.
- **Reservation:** stock held for an order for a limited time, pending payment.
- **Partial order:** an order that proceeds with only the items that could be reserved.
- **Compensating action:** an operation that undoes the effect of an earlier step, for example a refund. Used in sagas.
- **Terminal state:** an order state that can never change again.
- **p99 latency:** 99% of requests complete faster than this value.
