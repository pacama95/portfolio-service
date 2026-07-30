-- One GBP-denominated LSE position, for flows that exercise FX conversion and
-- pence-sterling price normalization end to end.
INSERT INTO transactions (
    id, user_id, ticker, transaction_type, asset_type, quantity, price, fees, currency,
    transaction_date, commission_currency, exchange, country, company_name,
    is_fractional, fractional_multiplier, notes, created_at, updated_at
) VALUES
    ('99999999-9999-9999-9999-999999999999', 'user-a', 'BARC', 'BUY'::transaction_type,
     'COMMON_STOCK'::asset_type, 10.000000, 2.500000, 0.000000, 'GBP'::currency_type,
     '2024-01-01', 'GBP'::currency_type, 'XLON', 'GB', 'Barclays',
     false, 1.0, null, '2024-01-01 00:00:00+00', '2024-01-01 00:00:00+00');
