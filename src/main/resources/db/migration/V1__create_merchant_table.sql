CREATE TABLE merchant (
    id UUID NOT NULL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(254) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    webhook_url VARCHAR(2048),
    api_key_hash VARCHAR(64) NOT NULL UNIQUE,
    deactivated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_merchant_status
        CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_merchant_status_deactivated_at
        CHECK (
            (status = 'ACTIVE' AND deactivated_at IS NULL)
            OR
            (status = 'INACTIVE' AND deactivated_at IS NOT NULL)
        )
);

CREATE UNIQUE INDEX ux_merchant_email_lower
ON merchant (LOWER(email));

CREATE FUNCTION set_merchant_updated_at()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_merchant_set_updated_at
BEFORE UPDATE ON merchant
FOR EACH ROW
EXECUTE FUNCTION set_merchant_updated_at();
