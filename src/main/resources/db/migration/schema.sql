-- Portfolio Service schema: per-user transaction ledger + shared market data store

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TYPE currency_type AS ENUM ('USD', 'EUR', 'GBP', 'CAD', 'JPY');
CREATE TYPE transaction_type AS ENUM ('BUY', 'SELL', 'DIVIDEND', 'SPLIT');
CREATE TYPE asset_type AS ENUM (
    'AMERICAN_DEPOSITARY_RECEIPT',
    'BOND',
    'BOND_FUND',
    'CLOSED_END_FUND',
    'COMMON_STOCK',
    'DEPOSITARY_RECEIPT',
    'DIGITAL_CURRENCY',
    'ETF',
    'EXCHANGE_TRADED_NOTE',
    'GLOBAL_DEPOSITARY_RECEIPT',
    'LIMITED_PARTNERSHIP',
    'MUTUAL_FUND',
    'PHYSICAL_CURRENCY',
    'PREFERRED_STOCK',
    'REIT',
    'RIGHT',
    'STRUCTURED_PRODUCT',
    'TRUST',
    'UNIT',
    'WARRANT'
);
CREATE TYPE quote_source AS ENUM ('PROVIDER', 'MANUAL');
CREATE TYPE spot_quote_kind AS ENUM ('PRICE', 'FX');
CREATE TYPE ingestion_run_status AS ENUM ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED');

CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ language 'plpgsql';

-- Per-user ledger (single source of truth)
CREATE TABLE transactions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id VARCHAR(128) NOT NULL,
    ticker VARCHAR(20) NOT NULL,
    transaction_type transaction_type NOT NULL DEFAULT 'BUY',
    asset_type asset_type NOT NULL,
    quantity DECIMAL(18, 6) NOT NULL,
    price DECIMAL(18, 6) NOT NULL,
    fees DECIMAL(18, 6) NOT NULL DEFAULT 0.00,
    currency currency_type NOT NULL,
    transaction_date DATE NOT NULL,
    commission_currency currency_type,
    exchange VARCHAR(20),
    country VARCHAR(50),
    company_name VARCHAR(255),
    is_fractional BOOLEAN NOT NULL DEFAULT FALSE,
    fractional_multiplier DECIMAL(10, 8) NOT NULL DEFAULT 1.0,
    notes TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_transactions_user_ticker_date ON transactions (user_id, ticker, transaction_date);
CREATE INDEX idx_transactions_user_date ON transactions (user_id, transaction_date);
CREATE INDEX idx_transactions_user_id ON transactions (user_id);

CREATE TRIGGER update_transactions_updated_at
    BEFORE UPDATE ON transactions
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- Shared market data store
CREATE TABLE price_history (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    symbol VARCHAR(32) NOT NULL,
    price_date DATE NOT NULL,
    close_price DECIMAL(18, 6) NOT NULL,
    adjusted_close_price DECIMAL(18, 6),
    currency currency_type NOT NULL DEFAULT 'USD',
    provider VARCHAR(64),
    fetched_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (symbol, price_date)
);

CREATE INDEX idx_price_history_symbol_date ON price_history (symbol, price_date);

CREATE TRIGGER update_price_history_updated_at
    BEFORE UPDATE ON price_history
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TABLE fx_rate_history (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    base_currency currency_type NOT NULL,
    quote_currency currency_type NOT NULL,
    rate_date DATE NOT NULL,
    rate DECIMAL(18, 8) NOT NULL,
    provider VARCHAR(64),
    fetched_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (base_currency, quote_currency, rate_date)
);

CREATE INDEX idx_fx_rate_history_pair_date ON fx_rate_history (base_currency, quote_currency, rate_date);

CREATE TRIGGER update_fx_rate_history_updated_at
    BEFORE UPDATE ON fx_rate_history
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TABLE spot_quotes (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    kind spot_quote_kind NOT NULL,
    symbol VARCHAR(32) NOT NULL,
    value DECIMAL(18, 8) NOT NULL,
    currency currency_type,
    base_currency currency_type,
    quote_currency currency_type,
    as_of TIMESTAMP WITH TIME ZONE NOT NULL,
    source quote_source NOT NULL DEFAULT 'PROVIDER',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (kind, symbol)
);

CREATE INDEX idx_spot_quotes_kind_symbol ON spot_quotes (kind, symbol);

CREATE TRIGGER update_spot_quotes_updated_at
    BEFORE UPDATE ON spot_quotes
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TABLE exchanges (
    code VARCHAR(20) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    country VARCHAR(100),
    currency VARCHAR(10) NOT NULL,
    price_currency VARCHAR(10) NOT NULL,
    price_multiplier DECIMAL(10, 6) NOT NULL DEFAULT 1.0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO exchanges (code, name, country, currency, price_currency, price_multiplier) VALUES
  ('XLON',   'London Stock Exchange',           'GB', 'GBP', 'GBp', 0.01),
  ('LSE',    'London Stock Exchange',           'GB', 'GBP', 'GBp', 0.01),
  ('NASDAQ', 'NASDAQ',                          'US', 'USD', 'USD', 1.0),
  ('NYSE',   'New York Stock Exchange',         'US', 'USD', 'USD', 1.0),
  ('XNYS',   'New York Stock Exchange (MIC)',   'US', 'USD', 'USD', 1.0),
  ('XNAS',   'NASDAQ Stock Market (MIC)',       'US', 'USD', 'USD', 1.0),
  ('ARCA',   'NYSE Arca',                       'US', 'USD', 'USD', 1.0),
  ('AMEX',   'NYSE American',                   'US', 'USD', 'USD', 1.0),
  ('XETRA',  'Deutsche Boerse Xetra',           'DE', 'EUR', 'EUR', 1.0),
  ('XFRA',   'Frankfurt Stock Exchange',        'DE', 'EUR', 'EUR', 1.0),
  ('XPAR',   'Euronext Paris',                  'FR', 'EUR', 'EUR', 1.0),
  ('XAMS',   'Euronext Amsterdam',              'NL', 'EUR', 'EUR', 1.0),
  ('XBRU',   'Euronext Brussels',               'BE', 'EUR', 'EUR', 1.0),
  ('XMIL',   'Borsa Italiana (Milan)',          'IT', 'EUR', 'EUR', 1.0),
  ('XSWX',   'SIX Swiss Exchange',              'CH', 'CHF', 'CHF', 1.0),
  ('BME',    'Bolsas y Mercados Españoles',     'ES', 'EUR', 'EUR', 1.0),
  ('XMAD',   'Bolsa de Madrid',                 'ES', 'EUR', 'EUR', 1.0),
  ('MC',     'Mercado Continuo (Spain)',        'ES', 'EUR', 'EUR', 1.0),
  ('TSX',    'Toronto Stock Exchange',          'CA', 'CAD', 'CAD', 1.0),
  ('XTSX',   'TSX Venture Exchange',            'CA', 'CAD', 'CAD', 1.0),
  ('XASX',   'Australian Securities Exchange',  'AU', 'AUD', 'AUD', 1.0),
  ('XHKG',   'Hong Kong Stock Exchange',        'HK', 'HKD', 'HKD', 1.0),
  ('XTKS',   'Tokyo Stock Exchange',            'JP', 'JPY', 'JPY', 1.0),
  ('XSTO',   'Nasdaq Stockholm',                'SE', 'SEK', 'SEK', 1.0),
  ('XOSL',   'Oslo Børs',                       'NO', 'NOK', 'NOK', 1.0);

CREATE TABLE price_ingestion_runs (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id VARCHAR(128),
    status ingestion_run_status NOT NULL DEFAULT 'PENDING',
    from_date DATE,
    to_date DATE,
    ticker VARCHAR(32),
    include_backfill BOOLEAN NOT NULL DEFAULT FALSE,
    symbols_requested INT NOT NULL DEFAULT 0,
    symbols_completed INT NOT NULL DEFAULT 0,
    error_message TEXT,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_price_ingestion_runs_created ON price_ingestion_runs (created_at DESC);

CREATE TRIGGER update_price_ingestion_runs_updated_at
    BEFORE UPDATE ON price_ingestion_runs
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- Known empty ranges from provider (avoid re-fetching non-trading gaps forever)
CREATE TABLE market_data_coverage (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    coverage_kind VARCHAR(16) NOT NULL,
    symbol VARCHAR(64) NOT NULL,
    from_date DATE NOT NULL,
    to_date DATE NOT NULL,
    provider VARCHAR(64),
    fetched_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (coverage_kind, symbol, from_date, to_date)
);
