INSERT INTO price_ingestion_runs (
    id, user_id, status, from_date, to_date, ticker, include_backfill,
    symbols_requested, symbols_completed, error_message, started_at, completed_at, created_at, updated_at
) VALUES
    ('33333333-3333-3333-3333-333333333333', 'user-a', 'COMPLETED'::ingestion_run_status,
     '2024-01-01', '2024-01-02', 'AAPL', false,
     1, 1, null, '2024-01-02 06:00:00+00', '2024-01-02 06:01:00+00',
     '2024-01-02 06:00:00+00', '2024-01-02 06:01:00+00');
