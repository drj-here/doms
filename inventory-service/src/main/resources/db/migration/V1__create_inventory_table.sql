CREATE TABLE products(
id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
sku VARCHAR(50) NOT NULL UNIQUE,
name VARCHAR(255) NOT NULL,
price NUMERIC(12, 2) NOT NULL CHECK (price > 0),
status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
updated_at TIMESTAMPTZ NOT NULL DEFAULT  now()
);

CREATE TABLE inventory(
product_id UUID PRIMARY KEY REFERENCES products(id),
available_qty INT NOT NULL CHECK (available_qty>=0),
updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE reservations(
id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
order_id UUID NOT NULL,
product_id UUID NOT NULL REFERENCES products(id),
qty INT NOT NULL CHECK (qty>0),
status VARCHAR(20) NOT NULL CHECK (status IN ('RESERVED', 'SOLD', 'RELEASED','REJECTED')),
created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
UNIQUE (order_id, product_id)
);

CREATE INDEX idx_reservations_reserved_created_at
ON reservations(created_at)
WHERE status= 'RESERVED';