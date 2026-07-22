-- Step 7 (§11): provider keys move to AES-GCM at rest; request_logs grows the
-- columns spend caps and opt-in content logging need.

ALTER TABLE providers RENAME COLUMN api_key TO encrypted_api_key;
ALTER TABLE providers ADD COLUMN nonce TEXT;

ALTER TABLE api_keys ADD COLUMN max_tokens_cap INTEGER;

ALTER TABLE request_logs ADD COLUMN cost_usd NUMERIC(12, 6);
ALTER TABLE request_logs ADD COLUMN prompt_excerpt TEXT;
ALTER TABLE request_logs ADD COLUMN response_excerpt TEXT;

CREATE INDEX idx_request_logs_api_key ON request_logs (api_key_id, ts);
