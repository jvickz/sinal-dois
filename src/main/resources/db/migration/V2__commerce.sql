CREATE TABLE commerce_catalog (
    sku varchar(64) PRIMARY KEY REFERENCES inventory (sku),
    name varchar(120) NOT NULL,
    description varchar(300) NOT NULL,
    category varchar(60) NOT NULL,
    price_cents integer NOT NULL CHECK (price_cents > 0),
    image_key varchar(40) NOT NULL
);

CREATE TABLE commerce_orders (
    id uuid PRIMARY KEY,
    buyer_id uuid NOT NULL,
    status varchar(24) NOT NULL CHECK (status IN (
        'AWAITING_PAYMENT', 'PAID', 'PICKING', 'SHIPPED', 'PAYMENT_FAILED', 'CANCELLED', 'EXPIRED'
    )),
    total_cents bigint NOT NULL CHECK (total_cents > 0),
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL CHECK (expires_at > created_at)
);

CREATE INDEX commerce_orders_buyer_recent ON commerce_orders (buyer_id, created_at DESC, id DESC);
CREATE INDEX commerce_orders_pending ON commerce_orders (expires_at, id) WHERE status = 'AWAITING_PAYMENT';

CREATE TABLE commerce_checkout_requests (
    buyer_id uuid NOT NULL,
    request_key varchar(128) NOT NULL,
    order_id uuid UNIQUE REFERENCES commerce_orders (id),
    PRIMARY KEY (buyer_id, request_key)
);

CREATE TABLE commerce_order_items (
    order_id uuid NOT NULL REFERENCES commerce_orders (id),
    sku varchar(64) NOT NULL REFERENCES inventory (sku),
    name varchar(120) NOT NULL,
    quantity integer NOT NULL CHECK (quantity BETWEEN 1 AND 99),
    unit_price_cents integer NOT NULL CHECK (unit_price_cents > 0),
    PRIMARY KEY (order_id, sku)
);

CREATE TABLE commerce_order_history (
    sequence bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id uuid NOT NULL REFERENCES commerce_orders (id),
    type varchar(32) NOT NULL,
    label varchar(180) NOT NULL,
    happened_at timestamptz NOT NULL
);

CREATE INDEX commerce_order_history_order ON commerce_order_history (order_id, sequence);

CREATE TABLE commerce_payment_events (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL REFERENCES commerce_orders (id),
    outcome varchar(8) NOT NULL CHECK (outcome IN ('APPROVED', 'DECLINED')),
    received_at timestamptz NOT NULL,
    processed_at timestamptz
);

CREATE INDEX commerce_payment_events_pending ON commerce_payment_events (received_at, id) WHERE processed_at IS NULL;
