CREATE TABLE inventory (
    sku varchar(64) PRIMARY KEY,
    available integer NOT NULL CHECK (available >= 0)
);

CREATE TABLE reservations (
    id uuid PRIMARY KEY,
    sku varchar(64) NOT NULL REFERENCES inventory (sku),
    quantity integer NOT NULL CHECK (quantity > 0),
    status varchar(12) NOT NULL CHECK (status IN ('HELD', 'CONFIRMED', 'CANCELLED', 'EXPIRED')),
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL CHECK (expires_at > created_at)
);

CREATE INDEX reservations_pending_expiry ON reservations (expires_at, id) WHERE status = 'HELD';

CREATE TABLE reservation_requests (
    request_key varchar(128) PRIMARY KEY,
    reservation_id uuid UNIQUE REFERENCES reservations (id)
);
