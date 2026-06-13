CREATE
EXTENSION IF NOT EXISTS pgcrypto;
CREATE
EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE properties
(
    id          UUID PRIMARY KEY        DEFAULT gen_random_uuid(),
    owner_id    TEXT           NOT NULL,
    address     TEXT           NOT NULL,
    description TEXT,
    price_night NUMERIC(10, 2) NOT NULL,
    status      TEXT           NOT NULL DEFAULT 'available',
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE TABLE bookings
(
    id          UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    property_id UUID        NOT NULL REFERENCES properties (id) ON DELETE CASCADE,
    tenant_id   TEXT        NOT NULL,
    start_date  DATE        NOT NULL,
    end_date    DATE        NOT NULL,
    status      TEXT        NOT NULL DEFAULT 'confirmed',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT bookings_date_order CHECK (start_date < end_date)
);

CREATE INDEX idx_bookings_property_status
    ON bookings (property_id, status);

CREATE
OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at
= now();
RETURN NEW;
END;
$$
LANGUAGE plpgsql;

CREATE TRIGGER trg_properties_updated_at
    BEFORE UPDATE
    ON properties
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_bookings_updated_at
    BEFORE UPDATE
    ON bookings
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

ALTER TABLE bookings
    ADD CONSTRAINT no_overlap EXCLUDE USING gist (
        property_id WITH =,
        daterange(start_date, end_date, '[)') WITH &&
    )
    WHERE (status = 'confirmed');
