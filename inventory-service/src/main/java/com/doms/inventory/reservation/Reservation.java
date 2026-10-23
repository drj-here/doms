package com.doms.inventory.reservation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "reservations")
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID orderId;

    @Column(nullable = false)
    private UUID productId;

    @Column(nullable = false)
    private int qty;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReservationStatus status;

    @Enumerated(EnumType.STRING)
    private RejectionReason rejectionReason;

    private BigDecimal unitPrice;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    protected Reservation() {
        // required by JPA
    }

    /** Stock was taken for this item. A price snapshot is mandatory. */
    public static Reservation reserved(UUID orderId, UUID productId, int qty, BigDecimal unitPrice) {
        Reservation r = new Reservation();
        r.orderId = orderId;
        r.productId = productId;
        r.qty = qty;
        r.status = ReservationStatus.RESERVED;
        r.unitPrice = unitPrice;
        return r;
    }

    /** No stock was taken for this item. A reason is mandatory and there is no price. */
    public static Reservation rejected(UUID orderId, UUID productId, int qty, RejectionReason reason) {
        Reservation r = new Reservation();
        r.orderId = orderId;
        r.productId = productId;
        r.qty = qty;
        r.status = ReservationStatus.REJECTED;
        r.rejectionReason = reason;
        return r;
    }

    public UUID getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public UUID getProductId() { return productId; }
    public int getQty() { return qty; }
    public ReservationStatus getStatus() { return status; }
    public RejectionReason getRejectionReason() { return rejectionReason; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
