# 03 — Low-Level Design: inventory-service

> **Status:** v1.0 — Approved
> **Last updated:** 2026-10-21
> **Depends on:** [01 — Problem Statement](01-problem-statement.md), [02 — HLD](02-hld.md)

---

## 1. Scope

`inventory-service` owns:

- the **product catalog** (read-only in Phase 1, seeded by Flyway)
- **stock levels**
- **reservations**: atomic, idempotent, per-item, with partial success

It exposes a **public** read API (products) and an **internal** API (reservations) called by `order-service`. It consumes `OrderConfirmed` and `OrderCancelled` from Kafka.

---

## 2. Package structure (package-by-feature)

```
com.doms.inventory
├── InventoryServiceApplication.java
├── product/
│   ├── Product.java                  # entity
│   ├── ProductStatus.java            # ACTIVE | INACTIVE
│   ├── ProductRepository.java
│   ├── ProductService.java
│   ├── ProductController.java        # GET /products, GET /products/{id}
│   └── ProductResponse.java          # DTO
├── stock/
│   └── InventoryRepository.java      # atomic reserve/release SQL
├── reservation/
│   ├── Reservation.java              # entity
│   ├── ReservationStatus.java        # RESERVED | SOLD | RELEASED | REJECTED
│   ├── RejectionReason.java          # OUT_OF_STOCK | PRODUCT_INACTIVE
│   ├── ReservationRepository.java
│   ├── ReservationService.java       # the reservation algorithm (§6)
│   ├── ReservationController.java    # POST /reservations
│   ├── ReserveRequest.java           # DTO (request)
│   ├── ReservationResponse.java      # DTO (response)
│   └── OrphanReservationSweeper.java # safety net (§8)
└── common/
    └── GlobalExceptionHandler.java   # one error format for every endpoint (§5)
```

**Rules**

- Controllers contain **no business logic**: they map HTTP to service calls.
- Services own **transactions** (`@Transactional`) and business rules.
- **Entities never leave the service layer**. Controllers return DTOs only.
- Classes are **package-private by default**; `public` only when another package needs them.

---

## 3. Data model

### 3.1 Tables

Defined in `V1__create_inventory_table.sql`, extended by `V3`.

| Table | Purpose | Key constraints |
|---|---|---|
| `products` | Catalog | PK `id`, `UNIQUE (sku)`, `CHECK (price > 0)` |
| `inventory` | Stock per product (1:1 with `products`) | PK/FK `product_id`, **`CHECK (available_qty >= 0)`**, the last line of defence against overselling |
| `reservations` | One row per (order, product) | PK `id`, **`UNIQUE (order_id, product_id)`** makes reserve idempotent; no FK on `order_id` (it belongs to another service) |

### 3.2 Migration `V3__add_snapshot_columns_to_reservations.sql`

An idempotent retry must return **exactly** the original answer. Price and rejection reason are therefore **stored**, not recomputed.

```sql
ALTER TABLE reservations
    ADD COLUMN unit_price       NUMERIC(12, 2),
    ADD COLUMN rejection_reason VARCHAR(30)
        CHECK (rejection_reason IN ('OUT_OF_STOCK', 'PRODUCT_INACTIVE'));

ALTER TABLE reservations
    ADD CONSTRAINT chk_reservations_status_fields CHECK (
        (status = 'REJECTED' AND rejection_reason IS NOT NULL AND unit_price IS NULL)
        OR
        (status <> 'REJECTED' AND rejection_reason IS NULL AND unit_price IS NOT NULL)
    );
```

V1 has already run, so it is frozen. Schema changes always go in a new migration.

### 3.3 Reservation lifecycle

```
                OrderConfirmed
  [RESERVED] ─────────────────► [SOLD]
      │
      │ OrderCancelled / orphan sweep
      ▼
  [RELEASED]

  [REJECTED]   (created directly; terminal)
```

- Terminal states: `SOLD`, `RELEASED`, `REJECTED`.
- Every transition is a **conditional update** (`WHERE status = 'RESERVED'`), so duplicate events are harmless no-ops.

---

## 4. API contracts

All JSON uses **camelCase**. Money is a JSON number with 2 decimals. IDs are UUID strings.

### 4.1 `GET /products` (public)

`200 OK`

```json
[
  { "id": "11111111-1111-1111-1111-000000000001", "name": "iPhone 15", "price": 57999.00 }
]
```

### 4.2 `GET /products/{id}` (public)

| Case | Status |
|---|---|
| Found | `200` + product |
| Valid UUID, not found | `404` |
| Not a UUID | `400` |

### 4.3 `POST /reservations` (internal, called by order-service only)

**Request**

```json
{
  "orderId": "a1b2c3d4-0000-0000-0000-000000000001",
  "items": [
    { "productId": "11111111-1111-1111-1111-000000000001", "qty": 1 },
    { "productId": "11111111-1111-1111-1111-000000000004", "qty": 2 }
  ]
}
```

| Field | Rule |
|---|---|
| `orderId` | Required, UUID |
| `items` | Required, 1–20 entries |
| `items[].productId` | Required, UUID, **unique within the request** |
| `items[].qty` | Required, 1–5 (configurable, see §9) |

**Response**

```json
{
  "orderId": "a1b2c3d4-0000-0000-0000-000000000001",
  "items": [
    {
      "productId": "11111111-1111-1111-1111-000000000001",
      "qty": 1,
      "status": "REJECTED",
      "reason": "OUT_OF_STOCK",
      "unitPrice": null
    },
    {
      "productId": "11111111-1111-1111-1111-000000000004",
      "qty": 2,
      "status": "RESERVED",
      "reason": null,
      "unitPrice": 37999.00
    }
  ]
}
```

- Items are **self-describing**. Consumers match items by `productId`, **never by array position**.
- Items are returned **sorted by `productId`**.

**Status codes**

| Case | Code | Body |
|---|---|---|
| New request, ≥1 item reserved | `201` | Per-item results |
| New request, all items rejected | `201` | Per-item results, all `REJECTED` |
| **Retry with an existing `orderId`** | **`200`** | **Stored original result**; nothing new is reserved |
| Validation failure (missing field, `qty` out of range, too many items) | `400` | Error details |
| Duplicate `productId` in `items` | `400` | Error details |
| Unknown `productId` | `422` | Lists the unknown IDs; nothing is reserved |

---

## 5. Error format

All errors use **RFC 9457 Problem Details** (`application/problem+json`), produced by `GlobalExceptionHandler` (`@RestControllerAdvice`) with Spring's built-in `ProblemDetail`.

```json
{
  "type": "about:blank",
  "title": "Unknown product",
  "status": 422,
  "detail": "Products not found: [11111111-1111-1111-1111-000000000099]",
  "instance": "/reservations"
}
```

- One format for every error, in every service.
- **Never leak stack traces or SQL** in error bodies.

---

## 6. Reservation algorithm

`ReservationService.reserve(request)` runs as **one transaction**:

```
1. VALIDATE (before touching data)
   - Bean Validation (@Valid): required fields, qty range, item count   → 400
   - productIds unique within the request                               → 400

2. IDEMPOTENCY CHECK
   - existing = reservations WHERE order_id = :orderId
   - if existing is not empty → return (existing, 200)                  ← replay, reserve nothing

3. LOAD PRODUCTS
   - products WHERE id IN (:productIds)
   - any id missing → 422, nothing reserved

4. FOR EACH item, IN productId ORDER (see note on deadlocks)
   - product INACTIVE   → reservation REJECTED, reason PRODUCT_INACTIVE
   - else atomic update:
         UPDATE inventory
         SET available_qty = available_qty - :qty, updated_at = now()
         WHERE product_id = :productId AND available_qty >= :qty
     - 1 row updated → reservation RESERVED, unit_price = product.price
     - 0 rows        → reservation REJECTED, reason OUT_OF_STOCK

5. INSERT all reservation rows

6. COMMIT → return (rows, 201)
```

### 6.1 Notes

**Why one transaction?** If the service crashes halfway, everything rolls back. No stock is taken without a matching reservation row, so the retry starts from a clean state.

**Deadlock prevention: process items in `productId` order.**

- Each atomic update **locks that inventory row until commit**.
- Without a fixed order, order A (items `[X, Y]`) and order B (items `[Y, X]`) can each hold one lock and wait for the other, causing a **deadlock**.
- Always locking in the **same global order** (sorted by `productId`) makes that impossible.

**Concurrent duplicate requests.** Two identical requests can both pass step 2 at the same moment. The second insert then fails on `UNIQUE (order_id, product_id)`, and its transaction rolls back, **returning its stock decrements too**. The service catches `DataIntegrityViolationException`, re-reads the stored rows in a new transaction, and returns them with `200`. Idempotency is guaranteed by the **database constraint**, not by the read in step 2.

**Lock duration.** Row locks are held for the whole transaction, so the transaction does **no remote calls and no slow work**: only the SQL above. This keeps flash-sale contention short.

---

## 7. Event consumers (Kafka; implemented in the Kafka step)

| Event | Action (one transaction, idempotent) |
|---|---|
| `OrderConfirmed` | `UPDATE reservations SET status = 'SOLD' WHERE order_id = :id AND status = 'RESERVED'` |
| `OrderCancelled` | `UPDATE reservations SET status = 'RELEASED' WHERE order_id = :id AND status = 'RESERVED' RETURNING product_id, qty`, then for each returned row: `UPDATE inventory SET available_qty = available_qty + :qty` |

- **Stock is returned only for rows this update actually moved to `RELEASED`.** A duplicate `OrderCancelled` moves 0 rows, so it returns 0 stock.
- `REJECTED` rows never took stock, so they are never released.

---

## 8. Orphan reservation sweep (safety net)

- `OrphanReservationSweeper` uses `@Scheduled(fixedDelayString = "${doms.inventory.orphan-sweep-interval}")`.
- It releases `RESERVED` rows with `created_at < now() - orphan-timeout`, using **the same code path as `OrderCancelled`**.
- It uses the partial index `idx_reservations_reserved_created_at`.
- It is a backstop for lost events and crashes. In normal operation, order-service's expiry job (5 min) cancels long before this (30 min) fires.

---

## 9. Configuration

```properties
doms.inventory.max-qty-per-item=5
doms.inventory.max-items-per-request=20
doms.inventory.orphan-timeout=30m
doms.inventory.orphan-sweep-interval=5m
```

Business limits live in configuration, not hardcoded constants.

---

## 10. Testing plan

| Level | Test | Tool |
|---|---|---|
| Unit | Validation rules; per-item outcome logic (active / inactive / out of stock) | JUnit 5 + Mockito |
| Integration | Full reserve flow against real Postgres | `@SpringBootTest` + **Testcontainers** |
| Integration | **Concurrency:** 50 parallel requests, stock = 1 → exactly 1 `RESERVED`, `available_qty = 0`, never negative | Testcontainers + `ExecutorService` |
| Integration | **Idempotency:** same `orderId` twice → second returns `200` with identical body; stock decremented once | Testcontainers |
| Integration | **Concurrent duplicates:** same `orderId` sent in parallel → exactly one set of rows | Testcontainers |
| Integration | Unknown product → `422`, stock unchanged | Testcontainers |
| Integration | Cancel / confirm consumers are idempotent (deliver each event twice) | Testcontainers + Kafka |

---

## 11. Decision log

| # | Decision | Rationale |
|---|---|---|
| L1 | Package-by-feature | Related code stays together, which scales better than package-by-layer |
| L2 | Entities never leave the service layer | API contract is independent of the schema |
| L3 | Store `unit_price` and `rejection_reason` on reservations (V3) | A retry must replay the original answer exactly |
| L4 | Whole reserve in one transaction | A crash leaves no half-reserved state |
| L5 | Lock inventory rows in `productId` order | Prevents deadlocks between multi-item orders |
| L6 | Idempotency enforced by `UNIQUE (order_id, product_id)`; the read check is only an optimisation | Correct under concurrent duplicates |
| L7 | Retry → `200` + stored result; unknown product → `422`; duplicate product → `400` | Retries recover lost responses; caller bugs fail loudly |
| L8 | RFC 9457 Problem Details for errors | Standard, consistent, supported by Spring |
| L9 | Release returns stock only for rows actually transitioned | Duplicate events can't double-release |
