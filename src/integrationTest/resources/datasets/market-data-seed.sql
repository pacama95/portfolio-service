INSERT INTO price_history (
    id, symbol, price_date, close_price, adjusted_close_price, currency, provider, fetched_at, created_at, updated_at
) VALUES
    ('eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee', 'AAPL', '2024-01-01', 100.000000, 100.000000, 'USD'::currency_type,
     'TWELVE_DATA', '2024-01-01 12:00:00+00', '2024-01-01 12:00:00+00', '2024-01-01 12:00:00+00'),
    ('ffffffff-ffff-ffff-ffff-ffffffffffff', 'AAPL', '2024-01-02', 101.000000, 101.000000, 'USD'::currency_type,
     'TWELVE_DATA', '2024-01-02 12:00:00+00', '2024-01-02 12:00:00+00', '2024-01-02 12:00:00+00');

INSERT INTO spot_quotes (
    id, kind, symbol, value, currency, base_currency, quote_currency, as_of, source, provider, created_at, updated_at
) VALUES
    ('11111111-1111-1111-1111-111111111111', 'PRICE'::spot_quote_kind, 'AAPL', 190.50000000,
     'USD'::currency_type, null, null, NOW(), 'PROVIDER'::quote_source, 'twelvedata',
     NOW(), NOW());

INSERT INTO fx_rate_history (
    id, base_currency, quote_currency, rate_date, rate, provider, fetched_at, created_at, updated_at
) VALUES
    ('22222222-2222-2222-2222-222222222222', 'EUR'::currency_type, 'USD'::currency_type, '2024-01-01',
     1.10000000, 'TWELVE_DATA', '2024-01-01 12:00:00+00', '2024-01-01 12:00:00+00', '2024-01-01 12:00:00+00');
