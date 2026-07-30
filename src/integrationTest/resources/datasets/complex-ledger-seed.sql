-- A quant-shaped ledger for daily-history construction: AAPL (USD) accumulates, pays a
-- dividend, and splits 4:1; BARC (GBP) is bought early and fully exited on the last day.
INSERT INTO transactions (
    user_id, ticker, transaction_type, asset_type, quantity, price, fees, currency,
    transaction_date, commission_currency, exchange, country, company_name,
    is_fractional, fractional_multiplier, notes, created_at, updated_at
) VALUES
    ('user-a', 'AAPL', 'BUY'::transaction_type, 'COMMON_STOCK'::asset_type,
     10.000000, 100.000000, 5.000000, 'USD'::currency_type,
     '2024-01-02', 'USD'::currency_type, 'NASDAQ', 'US', 'Apple',
     false, 1.0, null, '2024-01-02 00:00:00+00', '2024-01-02 00:00:00+00'),
    ('user-a', 'BARC', 'BUY'::transaction_type, 'COMMON_STOCK'::asset_type,
     10.000000, 2.500000, 0.000000, 'GBP'::currency_type,
     '2024-01-03', 'GBP'::currency_type, 'XLON', 'GB', 'Barclays',
     false, 1.0, null, '2024-01-03 00:00:00+00', '2024-01-03 00:00:00+00'),
    ('user-a', 'AAPL', 'BUY'::transaction_type, 'COMMON_STOCK'::asset_type,
     10.000000, 150.000000, 5.000000, 'USD'::currency_type,
     '2024-01-10', 'USD'::currency_type, 'NASDAQ', 'US', 'Apple',
     false, 1.0, null, '2024-01-10 00:00:00+00', '2024-01-10 00:00:00+00'),
    ('user-a', 'AAPL', 'DIVIDEND'::transaction_type, 'COMMON_STOCK'::asset_type,
     20.000000, 0.500000, 0.000000, 'USD'::currency_type,
     '2024-01-12', 'USD'::currency_type, 'NASDAQ', 'US', 'Apple',
     false, 1.0, null, '2024-01-12 00:00:00+00', '2024-01-12 00:00:00+00'),
    ('user-a', 'AAPL', 'SPLIT'::transaction_type, 'COMMON_STOCK'::asset_type,
     4.000000, 0.000000, 0.000000, 'USD'::currency_type,
     '2024-01-15', 'USD'::currency_type, 'NASDAQ', 'US', 'Apple',
     false, 1.0, null, '2024-01-15 00:00:00+00', '2024-01-15 00:00:00+00'),
    ('user-a', 'BARC', 'SELL'::transaction_type, 'COMMON_STOCK'::asset_type,
     10.000000, 3.000000, 1.000000, 'GBP'::currency_type,
     '2024-01-19', 'GBP'::currency_type, 'XLON', 'GB', 'Barclays',
     false, 1.0, null, '2024-01-19 00:00:00+00', '2024-01-19 00:00:00+00');
