-- Market data matching complex-ledger-seed.sql, fully store-resident (coverage rows below mean
-- no provider fetch happens). AAPL trades every weekday and drops 4:1 with the Jan 15 split;
-- BARC quotes stop after Jan 5 and resume Jan 16, so Jan 11-15 sit beyond the five-day
-- staleness floor; GBP/USD is quoted on weekdays only.
INSERT INTO price_history (symbol, price_date, close_price, adjusted_close_price, currency, provider, fetched_at) VALUES
    ('AAPL', '2024-01-02', 100.000000, 100.000000, 'USD'::currency_type, 'seed', '2024-01-20 00:00:00+00'),
    ('AAPL', '2024-01-03', 102.000000, 102.000000, 'USD'::currency_type, 'seed', '2024-01-20 00:00:00+00'),
    ('AAPL', '2024-01-04', 104.000000, 104.000000, 'USD'::currency_type, 'seed', '2024-01-20 00:00:00+00'),
    ('AAPL', '2024-01-05', 106.000000, 106.000000, 'USD'::currency_type, 'seed', '2024-01-20 00:00:00+00'),
    ('AAPL', '2024-01-08', 110.000000, 110.000000, 'USD'::currency_type, 'seed', '2024-01-20 00:00:00+00'),
    ('AAPL', '2024-01-09', 112.000000, 112.000000, 'USD'::currency_type, 'seed', '2024-01-20 00:00:00+00'),
    ('AAPL', '2024-01-10', 114.000000, 114.000000, 'USD'::currency_type, 'seed', '2024-01-20 00:00:00+00'),
    ('AAPL', '2024-01-11', 116.000000, 116.000000, 'USD'::currency_type, 'seed', '2024-01-20 00:00:00+00'),
    ('AAPL', '2024-01-12', 120.000000, 120.000000, 'USD'::currency_type, 'seed', '2024-01-20 00:00:00+00'),
    ('AAPL', '2024-01-15', 30.000000, 30.000000, 'USD'::currency_type, 'seed', '2024-01-20 00:00:00+00'),
    ('AAPL', '2024-01-16', 31.000000, 31.000000, 'USD'::currency_type, 'seed', '2024-01-20 00:00:00+00'),
    ('AAPL', '2024-01-17', 32.000000, 32.000000, 'USD'::currency_type, 'seed', '2024-01-20 00:00:00+00'),
    ('AAPL', '2024-01-18', 33.000000, 33.000000, 'USD'::currency_type, 'seed', '2024-01-20 00:00:00+00'),
    ('AAPL', '2024-01-19', 34.000000, 34.000000, 'USD'::currency_type, 'seed', '2024-01-20 00:00:00+00'),
    ('BARC', '2024-01-03', 2.500000, 2.500000, 'GBP'::currency_type, 'seed', '2024-01-20 00:00:00+00'),
    ('BARC', '2024-01-04', 2.600000, 2.600000, 'GBP'::currency_type, 'seed', '2024-01-20 00:00:00+00'),
    ('BARC', '2024-01-05', 2.600000, 2.600000, 'GBP'::currency_type, 'seed', '2024-01-20 00:00:00+00'),
    ('BARC', '2024-01-16', 2.800000, 2.800000, 'GBP'::currency_type, 'seed', '2024-01-20 00:00:00+00'),
    ('BARC', '2024-01-17', 2.800000, 2.800000, 'GBP'::currency_type, 'seed', '2024-01-20 00:00:00+00'),
    ('BARC', '2024-01-18', 2.900000, 2.900000, 'GBP'::currency_type, 'seed', '2024-01-20 00:00:00+00'),
    ('BARC', '2024-01-19', 3.000000, 3.000000, 'GBP'::currency_type, 'seed', '2024-01-20 00:00:00+00');

INSERT INTO fx_rate_history (base_currency, quote_currency, rate_date, rate, provider, fetched_at) VALUES
    ('GBP'::currency_type, 'USD'::currency_type, '2024-01-02', 1.25000000, 'seed', '2024-01-20 00:00:00+00'),
    ('GBP'::currency_type, 'USD'::currency_type, '2024-01-03', 1.25000000, 'seed', '2024-01-20 00:00:00+00'),
    ('GBP'::currency_type, 'USD'::currency_type, '2024-01-04', 1.25000000, 'seed', '2024-01-20 00:00:00+00'),
    ('GBP'::currency_type, 'USD'::currency_type, '2024-01-05', 1.25000000, 'seed', '2024-01-20 00:00:00+00'),
    ('GBP'::currency_type, 'USD'::currency_type, '2024-01-08', 1.26000000, 'seed', '2024-01-20 00:00:00+00'),
    ('GBP'::currency_type, 'USD'::currency_type, '2024-01-09', 1.26000000, 'seed', '2024-01-20 00:00:00+00'),
    ('GBP'::currency_type, 'USD'::currency_type, '2024-01-10', 1.26000000, 'seed', '2024-01-20 00:00:00+00'),
    ('GBP'::currency_type, 'USD'::currency_type, '2024-01-11', 1.26000000, 'seed', '2024-01-20 00:00:00+00'),
    ('GBP'::currency_type, 'USD'::currency_type, '2024-01-12', 1.27000000, 'seed', '2024-01-20 00:00:00+00'),
    ('GBP'::currency_type, 'USD'::currency_type, '2024-01-15', 1.27000000, 'seed', '2024-01-20 00:00:00+00'),
    ('GBP'::currency_type, 'USD'::currency_type, '2024-01-16', 1.28000000, 'seed', '2024-01-20 00:00:00+00'),
    ('GBP'::currency_type, 'USD'::currency_type, '2024-01-17', 1.28000000, 'seed', '2024-01-20 00:00:00+00'),
    ('GBP'::currency_type, 'USD'::currency_type, '2024-01-18', 1.28000000, 'seed', '2024-01-20 00:00:00+00'),
    ('GBP'::currency_type, 'USD'::currency_type, '2024-01-19', 1.30000000, 'seed', '2024-01-20 00:00:00+00');

-- Ranges are marked covered so the read path treats calendar gaps (weekends, the BARC hole)
-- as authoritative instead of asking a provider to fill them.
INSERT INTO market_data_coverage (coverage_kind, symbol, from_date, to_date, provider, fetched_at) VALUES
    ('PRICE', 'AAPL', '2023-12-01', '2024-02-01', 'seed', '2024-01-20 00:00:00+00'),
    ('PRICE', 'BARC', '2023-12-01', '2024-02-01', 'seed', '2024-01-20 00:00:00+00'),
    ('FX', 'GBP/USD', '2023-12-01', '2024-02-01', 'seed', '2024-01-20 00:00:00+00');
