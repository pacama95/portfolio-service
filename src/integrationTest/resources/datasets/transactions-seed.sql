INSERT INTO transactions (
    id, user_id, ticker, transaction_type, asset_type, quantity, price, fees, currency,
    transaction_date, commission_currency, exchange, country, company_name,
    is_fractional, fractional_multiplier, notes, created_at, updated_at
) VALUES
    ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'user-a', 'AAPL', 'BUY'::transaction_type, 'COMMON_STOCK'::asset_type,
     10.000000, 100.000000, 1.000000, 'USD'::currency_type,
     '2024-01-01', 'USD'::currency_type, 'NASDAQ', 'US', 'Apple',
     false, 1.0, null, '2024-01-01 00:00:00+00', '2024-01-01 00:00:00+00'),
    ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'user-a', 'AAPL', 'SELL'::transaction_type, 'COMMON_STOCK'::asset_type,
     2.000000, 120.000000, 0.000000, 'USD'::currency_type,
     '2024-01-05', 'USD'::currency_type, 'NASDAQ', 'US', 'Apple',
     false, 1.0, null, '2024-01-05 00:00:00+00', '2024-01-05 00:00:00+00'),
    ('cccccccc-cccc-cccc-cccc-cccccccccccc', 'user-a', 'MSFT', 'BUY'::transaction_type, 'COMMON_STOCK'::asset_type,
     5.000000, 200.000000, 0.000000, 'USD'::currency_type,
     '2024-01-02', 'USD'::currency_type, 'NASDAQ', 'US', 'Microsoft',
     false, 1.0, null, '2024-01-02 00:00:00+00', '2024-01-02 00:00:00+00'),
    ('dddddddd-dddd-dddd-dddd-dddddddddddd', 'user-b', 'GOOG', 'BUY'::transaction_type, 'COMMON_STOCK'::asset_type,
     3.000000, 150.000000, 0.000000, 'USD'::currency_type,
     '2024-01-03', 'USD'::currency_type, 'NASDAQ', 'US', 'Alphabet',
     false, 1.0, null, '2024-01-03 00:00:00+00', '2024-01-03 00:00:00+00');
