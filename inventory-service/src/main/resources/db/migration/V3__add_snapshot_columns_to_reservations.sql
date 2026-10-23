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