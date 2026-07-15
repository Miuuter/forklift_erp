CREATE TABLE request_idempotency (
    scope VARCHAR(50) NOT NULL,
    request_id VARCHAR(120) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (scope, request_id),
    KEY idx_request_idempotency_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
