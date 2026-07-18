INSERT INTO price_ingestion_runs (
    id, user_id, status, from_date, to_date, ticker, include_backfill,
    symbols_requested, symbols_completed, error_message, started_at, completed_at, created_at, updated_at
) VALUES
    ('44444444-4444-4444-4444-444444444444', 'user-a', 'PENDING'::ingestion_run_status,
     '2024-01-01', '2024-01-02', 'AAPL', false,
     0, 0, null, null, null,
     '2024-01-02 05:00:00+00', '2024-01-02 05:00:00+00');
