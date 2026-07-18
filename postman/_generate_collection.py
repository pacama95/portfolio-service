#!/usr/bin/env python3
"""Generate Portfolio Service Postman collection + environment. Run once to regenerate."""

from __future__ import annotations

import json
import uuid
from pathlib import Path

OUT_DIR = Path(__file__).resolve().parent
COLLECTION_UID = str(uuid.uuid4())
ENV_UID = str(uuid.uuid4())


def var(key: str, value: str, description: str = "") -> dict:
    item = {"key": key, "value": value, "type": "string"}
    if description:
        item["description"] = description
    return item


def header_user() -> list[dict]:
    return [
        {
            "key": "X-User-Id",
            "value": "{{userId}}",
            "type": "text",
        }
    ]


def json_header() -> list[dict]:
    return header_user() + [
        {"key": "Content-Type", "value": "application/json", "type": "text"}
    ]


def url(path: str, query: list[dict] | None = None) -> dict:
    segments = [s for s in path.strip("/").split("/") if s]
    raw = "{{baseUrl}}" + path
    if query:
        qs = "&".join(f"{q['key']}={q['value']}" for q in query if not q.get("disabled"))
        if qs:
            raw = f"{raw}?{qs}"
    return {
        "raw": raw,
        "host": ["{{baseUrl}}"],
        "path": segments,
        **({"query": query} if query else {}),
    }


def test_script(lines: list[str]) -> dict:
    return {
        "listen": "test",
        "script": {"type": "text/javascript", "exec": lines},
    }


def request_item(
    name: str,
    method: str,
    path: str,
    *,
    description: str = "",
    body: dict | None = None,
    query: list[dict] | None = None,
    tests: list[str] | None = None,
    include_user_header: bool = True,
    content_type: bool = False,
) -> dict:
    headers: list[dict] = []
    if include_user_header:
        headers.extend(header_user())
    if content_type or body is not None:
        headers.append({"key": "Content-Type", "value": "application/json", "type": "text"})

    req: dict = {
        "method": method,
        "header": headers,
        "url": url(path, query),
    }
    if description:
        req["description"] = description
    if body is not None:
        req["body"] = {
            "mode": "raw",
            "raw": json.dumps(body, indent=2),
            "options": {"raw": {"language": "json"}},
        }

    item: dict = {"name": name, "request": req, "response": []}
    if tests:
        item["event"] = [test_script(tests)]
    return item


def folder(name: str, items: list, description: str = "") -> dict:
    result = {"name": name, "item": items}
    if description:
        result["description"] = description
    return result


# --- Example payloads ---

BUY_AAPL = {
    "ticker": "AAPL",
    "transactionType": "BUY",
    "assetType": "COMMON_STOCK",
    "quantity": 10,
    "price": 100,
    "fees": 1,
    "currency": "USD",
    "transactionDate": "2024-01-15",
    "notes": "Initial Apple position",
    "exchange": "NASDAQ",
    "country": "US",
    "companyName": "Apple Inc.",
}

BUY_MSFT = {
    "ticker": "MSFT",
    "transactionType": "BUY",
    "assetType": "COMMON_STOCK",
    "quantity": 5,
    "price": 200,
    "fees": 0.5,
    "currency": "USD",
    "transactionDate": "2024-02-01",
    "notes": "Microsoft starter lot",
    "exchange": "NASDAQ",
    "country": "US",
    "companyName": "Microsoft Corporation",
}

SELL_AAPL = {
    "ticker": "AAPL",
    "transactionType": "SELL",
    "assetType": "COMMON_STOCK",
    "quantity": 3,
    "price": 150,
    "fees": 1,
    "currency": "USD",
    "transactionDate": "2024-06-01",
    "notes": "Partial take-profit",
    "exchange": "NASDAQ",
    "country": "US",
    "companyName": "Apple Inc.",
}

DIVIDEND_AAPL = {
    "ticker": "AAPL",
    "transactionType": "DIVIDEND",
    "assetType": "COMMON_STOCK",
    "quantity": 10,
    "price": 0.24,
    "fees": 0,
    "currency": "USD",
    "transactionDate": "2024-05-15",
    "notes": "Quarterly dividend",
    "exchange": "NASDAQ",
    "country": "US",
    "companyName": "Apple Inc.",
}

UPDATE_TX = {
    "quantity": 12,
    "price": 105,
    "fees": 1.5,
    "notes": "Corrected lot size and price",
    "companyName": "Apple Inc.",
}

UPDATE_PRICE = {"price": 185.5, "currency": "USD"}

INGESTION = {
    "from": "{{fromDate}}",
    "to": "{{toDate}}",
    "ticker": "{{ticker}}",
    "includeBackfill": False,
}


def status_tests(code: int, extra: list[str] | None = None) -> list[str]:
    lines = [
        f'pm.test("Status is {code}", function () {{',
        f"    pm.response.to.have.status({code});",
        "});",
    ]
    if extra:
        lines.extend(extra)
    return lines


def save_id_tests(var_name: str = "transactionId", status: int = 201) -> list[str]:
    return status_tests(
        status,
        [
            "const json = pm.response.json();",
            'pm.test("Response has id", function () {',
            '    pm.expect(json).to.have.property("id");',
            "});",
            f'pm.collectionVariables.set("{var_name}", json.id);',
            f'console.log("Saved {var_name}=" + json.id);',
        ],
    )


# === All Endpoints (manual / one-click) ===

transactions_folder = folder(
    "Transactions",
    [
        request_item(
            "Create transaction (BUY AAPL)",
            "POST",
            "/api/transactions",
            description="Creates a BUY transaction. Saves `transactionId` for follow-up requests.",
            body=BUY_AAPL,
            content_type=True,
            tests=save_id_tests("transactionId", 201),
        ),
        request_item(
            "Create transaction (BUY MSFT)",
            "POST",
            "/api/transactions",
            body=BUY_MSFT,
            content_type=True,
            tests=save_id_tests("msftTransactionId", 201),
        ),
        request_item(
            "Create transaction (SELL AAPL)",
            "POST",
            "/api/transactions",
            body=SELL_AAPL,
            content_type=True,
            tests=save_id_tests("sellTransactionId", 201),
        ),
        request_item(
            "Create transaction (DIVIDEND AAPL)",
            "POST",
            "/api/transactions",
            body=DIVIDEND_AAPL,
            content_type=True,
            tests=save_id_tests("dividendTransactionId", 201),
        ),
        request_item(
            "Get transaction by id",
            "GET",
            "/api/transactions/{{transactionId}}",
            tests=status_tests(200),
        ),
        request_item(
            "List all transactions",
            "GET",
            "/api/transactions",
            tests=status_tests(
                200,
                [
                    "pm.test('Body is an array', function () {",
                    "    pm.expect(pm.response.json()).to.be.an('array');",
                    "});",
                ],
            ),
        ),
        request_item(
            "List transactions by ticker",
            "GET",
            "/api/transactions/ticker/{{ticker}}",
            tests=status_tests(200),
        ),
        request_item(
            "Search transactions",
            "GET",
            "/api/transactions/search",
            query=[
                {"key": "ticker", "value": "{{ticker}}"},
                {"key": "type", "value": "BUY"},
                {"key": "fromDate", "value": "{{fromDate}}"},
                {"key": "toDate", "value": "{{toDate}}"},
                {"key": "sort", "value": "DESC"},
            ],
            tests=status_tests(200),
        ),
        request_item(
            "Update transaction",
            "PUT",
            "/api/transactions/{{transactionId}}",
            body=UPDATE_TX,
            content_type=True,
            tests=status_tests(200),
        ),
        request_item(
            "Count all transactions",
            "GET",
            "/api/transactions/count",
            tests=status_tests(200),
        ),
        request_item(
            "Count transactions by ticker",
            "GET",
            "/api/transactions/count/{{ticker}}",
            tests=status_tests(200),
        ),
        request_item(
            "Delete transaction",
            "DELETE",
            "/api/transactions/{{transactionId}}",
            description="Deletes the transaction stored in `transactionId`. Prefer runner folders for safe sequences.",
            tests=status_tests(204),
        ),
    ],
)

positions_folder = folder(
    "Positions",
    [
        request_item("List all positions", "GET", "/api/positions", tests=status_tests(200)),
        request_item("List active positions", "GET", "/api/positions/active", tests=status_tests(200)),
        request_item(
            "Get position by ticker",
            "GET",
            "/api/positions/ticker/{{ticker}}",
            tests=status_tests(200),
        ),
        request_item(
            "Position exists by ticker",
            "GET",
            "/api/positions/ticker/{{ticker}}/exists",
            tests=status_tests(200),
        ),
        request_item("Count all positions", "GET", "/api/positions/count", tests=status_tests(200)),
        request_item(
            "Count active positions",
            "GET",
            "/api/positions/count/active",
            tests=status_tests(200),
        ),
        request_item(
            "Update market price",
            "PUT",
            "/api/positions/ticker/{{ticker}}/price",
            body=UPDATE_PRICE,
            content_type=True,
            tests=status_tests(200),
        ),
    ],
)

portfolio_folder = folder(
    "Portfolio",
    [
        request_item("Portfolio summary (all)", "GET", "/api/portfolio/summary", tests=status_tests(200)),
        request_item(
            "Portfolio summary (active)",
            "GET",
            "/api/portfolio/summary/active",
            tests=status_tests(200),
        ),
    ],
)

daily_history_folder = folder(
    "Daily History",
    [
        request_item(
            "Daily position snapshots",
            "GET",
            "/api/daily-history/positions",
            query=[
                {"key": "from", "value": "{{fromDate}}"},
                {"key": "to", "value": "{{toDate}}"},
                {"key": "ticker", "value": "{{ticker}}"},
            ],
            tests=status_tests(200),
        ),
        request_item(
            "Price history",
            "GET",
            "/api/daily-history/prices",
            query=[
                {"key": "symbols", "value": "{{ticker}},MSFT"},
                {"key": "from", "value": "{{fromDate}}"},
                {"key": "to", "value": "{{toDate}}"},
            ],
            tests=status_tests(200),
        ),
        request_item(
            "Portfolio valuation history",
            "GET",
            "/api/daily-history/portfolio/valuation",
            query=[
                {"key": "from", "value": "{{fromDate}}"},
                {"key": "to", "value": "{{toDate}}"},
            ],
            tests=status_tests(200),
        ),
        request_item(
            "Capital flows",
            "GET",
            "/api/daily-history/portfolio/capital-flows",
            query=[
                {"key": "from", "value": "{{fromDate}}"},
                {"key": "to", "value": "{{toDate}}"},
            ],
            tests=status_tests(200),
        ),
        request_item(
            "Performance inputs",
            "GET",
            "/api/daily-history/portfolio/performance-inputs",
            query=[
                {"key": "from", "value": "{{fromDate}}"},
                {"key": "to", "value": "{{toDate}}"},
            ],
            tests=status_tests(200),
        ),
    ],
)

price_ingestion_folder = folder(
    "Price Ingestion",
    [
        request_item(
            "Trigger ingestion run",
            "POST",
            "/api/price-ingestion/runs",
            description="Async job. Expects 202 and stores `runId`. Requires Twelve Data API key for real market data.",
            body=INGESTION,
            content_type=True,
            tests=status_tests(
                202,
                [
                    "const json = pm.response.json();",
                    'pm.test("Has runId", function () {',
                    '    pm.expect(json).to.have.property("runId");',
                    "});",
                    'pm.collectionVariables.set("runId", json.runId);',
                ],
            ),
        ),
        request_item(
            "Latest ingestion run",
            "GET",
            "/api/price-ingestion/runs/latest",
            tests=status_tests(200),
        ),
    ],
)

health_folder = folder(
    "Health (no auth)",
    [
        request_item(
            "Health check",
            "GET",
            "/q/health",
            include_user_header=False,
            tests=status_tests(200),
        ),
        request_item(
            "OpenAPI document",
            "GET",
            "/q/openapi",
            include_user_header=False,
            tests=status_tests(200),
        ),
    ],
)

all_endpoints = folder(
    "01 - All Endpoints",
    [
        transactions_folder,
        positions_folder,
        portfolio_folder,
        daily_history_folder,
        price_ingestion_folder,
        health_folder,
    ],
    description=(
        "Every public endpoint with ready-to-send example data. "
        "Collection header X-User-Id and variables make each request one-click."
    ),
)


# === Runners (ordered Collection Runner scenarios) ===

runner_crud = folder(
    "Runner A — Transaction CRUD lifecycle",
    [
        request_item(
            "1. Create BUY AAPL",
            "POST",
            "/api/transactions",
            body=BUY_AAPL,
            content_type=True,
            tests=save_id_tests("transactionId", 201)
            + [
                'pm.test("Ticker is AAPL", function () {',
                '    pm.expect(pm.response.json().ticker).to.eql("AAPL");',
                "});",
            ],
        ),
        request_item(
            "2. Get created transaction",
            "GET",
            "/api/transactions/{{transactionId}}",
            tests=status_tests(
                200,
                [
                    'pm.test("Id matches", function () {',
                    '    pm.expect(pm.response.json().id).to.eql(pm.collectionVariables.get("transactionId"));',
                    "});",
                ],
            ),
        ),
        request_item(
            "3. Update transaction",
            "PUT",
            "/api/transactions/{{transactionId}}",
            body=UPDATE_TX,
            content_type=True,
            tests=status_tests(
                200,
                [
                    'pm.test("Quantity updated", function () {',
                    "    pm.expect(Number(pm.response.json().quantity)).to.eql(12);",
                    "});",
                ],
            ),
        ),
        request_item(
            "4. Search by ticker",
            "GET",
            "/api/transactions/search",
            query=[
                {"key": "ticker", "value": "{{ticker}}"},
                {"key": "sort", "value": "DESC"},
            ],
            tests=status_tests(
                200,
                [
                    "pm.test('At least one result', function () {",
                    "    pm.expect(pm.response.json().length).to.be.above(0);",
                    "});",
                ],
            ),
        ),
        request_item(
            "5. Count by ticker",
            "GET",
            "/api/transactions/count/{{ticker}}",
            tests=status_tests(
                200,
                [
                    "pm.test('Count is positive', function () {",
                    "    pm.expect(Number(pm.response.text())).to.be.above(0);",
                    "});",
                ],
            ),
        ),
        request_item(
            "6. Delete transaction",
            "DELETE",
            "/api/transactions/{{transactionId}}",
            tests=status_tests(204),
        ),
        request_item(
            "7. Get deleted → 404",
            "GET",
            "/api/transactions/{{transactionId}}",
            tests=status_tests(404),
        ),
    ],
    description="Create → get → update → search → count → delete → verify 404. Run this folder in Collection Runner.",
)

runner_buy_sell = folder(
    "Runner B — Buy then sell portfolio flow",
    [
        request_item(
            "1. BUY AAPL (10 @ 100)",
            "POST",
            "/api/transactions",
            body=BUY_AAPL,
            content_type=True,
            tests=save_id_tests("transactionId", 201),
        ),
        request_item(
            "2. BUY MSFT (5 @ 200)",
            "POST",
            "/api/transactions",
            body=BUY_MSFT,
            content_type=True,
            tests=save_id_tests("msftTransactionId", 201),
        ),
        request_item(
            "3. SELL AAPL (3 @ 150)",
            "POST",
            "/api/transactions",
            body=SELL_AAPL,
            content_type=True,
            tests=save_id_tests("sellTransactionId", 201),
        ),
        request_item(
            "4. Active positions",
            "GET",
            "/api/positions/active",
            tests=status_tests(
                200,
                [
                    "pm.test('Has positions', function () {",
                    "    pm.expect(pm.response.json().length).to.be.above(0);",
                    "});",
                ],
            ),
        ),
        request_item(
            "5. Get AAPL position",
            "GET",
            "/api/positions/ticker/AAPL",
            tests=status_tests(
                200,
                [
                    'pm.test("AAPL still active with remaining shares", function () {',
                    "    const p = pm.response.json();",
                    '    pm.expect(p.ticker).to.eql("AAPL");',
                    "    pm.expect(Number(p.totalQuantity)).to.eql(7);",
                    "});",
                ],
            ),
        ),
        request_item(
            "6. Update AAPL market price",
            "PUT",
            "/api/positions/ticker/AAPL/price",
            body=UPDATE_PRICE,
            content_type=True,
            tests=status_tests(
                200,
                [
                    "pm.test('Current price updated', function () {",
                    "    pm.expect(Number(pm.response.json().currentPrice)).to.eql(185.5);",
                    "});",
                ],
            ),
        ),
        request_item(
            "7. Portfolio summary (active)",
            "GET",
            "/api/portfolio/summary/active",
            tests=status_tests(
                200,
                [
                    'pm.test("Has valuation fields", function () {',
                    "    const s = pm.response.json();",
                    '    pm.expect(s).to.have.property("totalMarketValue");',
                    '    pm.expect(s).to.have.property("activePositions");',
                    "});",
                ],
            ),
        ),
        request_item(
            "8. Capital flows",
            "GET",
            "/api/daily-history/portfolio/capital-flows",
            query=[
                {"key": "from", "value": "{{fromDate}}"},
                {"key": "to", "value": "{{toDate}}"},
            ],
            tests=status_tests(
                200,
                [
                    'pm.test("Has flows array", function () {',
                    '    pm.expect(pm.response.json()).to.have.property("flows");',
                    "});",
                ],
            ),
        ),
    ],
    description=(
        "Seeds a multi-ticker book (BUY AAPL, BUY MSFT, SELL AAPL), then checks positions, "
        "manual mark-to-market, summary, and capital flows."
    ),
)

runner_history = folder(
    "Runner C — Daily history & ingestion",
    [
        request_item(
            "1. Ensure BUY AAPL exists",
            "POST",
            "/api/transactions",
            body=BUY_AAPL,
            content_type=True,
            tests=save_id_tests("transactionId", 201),
        ),
        request_item(
            "2. Daily position snapshots",
            "GET",
            "/api/daily-history/positions",
            query=[
                {"key": "from", "value": "{{fromDate}}"},
                {"key": "to", "value": "{{toDate}}"},
                {"key": "ticker", "value": "{{ticker}}"},
            ],
            tests=status_tests(200),
        ),
        request_item(
            "3. Trigger price ingestion",
            "POST",
            "/api/price-ingestion/runs",
            body=INGESTION,
            content_type=True,
            tests=[
                "pm.test('Accepted or conflict (already running)', function () {",
                "    pm.expect([202, 409]).to.include(pm.response.code);",
                "});",
                "if (pm.response.code === 202) {",
                "    const json = pm.response.json();",
                '    pm.collectionVariables.set("runId", json.runId);',
                "}",
            ],
        ),
        request_item(
            "4. Latest ingestion run",
            "GET",
            "/api/price-ingestion/runs/latest",
            tests=[
                "pm.test('200 or 404 if never run', function () {",
                "    pm.expect([200, 404]).to.include(pm.response.code);",
                "});",
            ],
        ),
        request_item(
            "5. Price history",
            "GET",
            "/api/daily-history/prices",
            query=[
                {"key": "symbols", "value": "{{ticker}}"},
                {"key": "from", "value": "{{fromDate}}"},
                {"key": "to", "value": "{{toDate}}"},
            ],
            tests=status_tests(200),
        ),
        request_item(
            "6. Portfolio valuation history",
            "GET",
            "/api/daily-history/portfolio/valuation",
            query=[
                {"key": "from", "value": "{{fromDate}}"},
                {"key": "to", "value": "{{toDate}}"},
            ],
            tests=status_tests(200),
        ),
        request_item(
            "7. Performance inputs",
            "GET",
            "/api/daily-history/portfolio/performance-inputs",
            query=[
                {"key": "from", "value": "{{fromDate}}"},
                {"key": "to", "value": "{{toDate}}"},
            ],
            tests=status_tests(
                200,
                [
                    'pm.test("Has valuations and capitalFlows", function () {',
                    "    const body = pm.response.json();",
                    '    pm.expect(body).to.have.property("valuations");',
                    '    pm.expect(body).to.have.property("capitalFlows");',
                    "});",
                ],
            ),
        ),
    ],
    description=(
        "Ledger-derived history plus optional Twelve Data ingestion. "
        "Ingestion may 409 if a run is already in progress; price history may be empty without a completed run."
    ),
)

runner_auth = folder(
    "Runner D — Auth guard (missing X-User-Id)",
    [
        request_item(
            "Create without X-User-Id → 400",
            "POST",
            "/api/transactions",
            body=BUY_AAPL,
            content_type=True,
            include_user_header=False,
            tests=status_tests(
                400,
                [
                    'pm.test("MISSING_USER_ID error", function () {',
                    "    const json = pm.response.json();",
                    '    pm.expect(json.error).to.eql("MISSING_USER_ID");',
                    "});",
                ],
            ),
        ),
        request_item(
            "List without X-User-Id → 400",
            "GET",
            "/api/transactions",
            include_user_header=False,
            tests=status_tests(400),
        ),
        request_item(
            "Health still works without header",
            "GET",
            "/q/health",
            include_user_header=False,
            tests=status_tests(200),
        ),
    ],
    description="Verifies UserIdentityFilter rejects /api/* without X-User-Id while /q/health stays open.",
)

runner_isolation = folder(
    "Runner E — User isolation",
    [
        request_item(
            "1. Create as user A",
            "POST",
            "/api/transactions",
            body={**BUY_AAPL, "notes": "Owned by user A"},
            content_type=True,
            tests=save_id_tests("transactionId", 201)
            + [
                'const uid = pm.environment.get("userId") || pm.collectionVariables.get("userId");',
                'pm.collectionVariables.set("userA", uid);',
            ],
        ),
        {
            "name": "2. Switch to user B (pre-request)",
            "event": [
                {
                    "listen": "prerequest",
                    "script": {
                        "type": "text/javascript",
                        "exec": [
                            "// Prefer environment (wins over collection vars in Postman resolution).",
                            'const current = pm.environment.get("userId") || pm.collectionVariables.get("userId") || "postman-demo-user";',
                            'pm.collectionVariables.set("userA", current);',
                            'pm.environment.set("userId", "postman-user-b");',
                            'pm.collectionVariables.set("userId", "postman-user-b");',
                            'console.log("Switched userId to postman-user-b (was " + current + ")");',
                        ],
                    },
                },
                test_script(
                    status_tests(
                        404,
                        [
                            'pm.test("User B cannot see User A transaction", function () {',
                            "    pm.expect(pm.response.code).to.eql(404);",
                            "});",
                        ],
                    )
                ),
            ],
            "request": {
                "method": "GET",
                "header": header_user(),
                "url": url("/api/transactions/{{transactionId}}"),
                "description": "Temporarily sets userId to postman-user-b, then expects 404.",
            },
            "response": [],
        },
        {
            "name": "3. Restore user A and verify visible",
            "event": [
                {
                    "listen": "prerequest",
                    "script": {
                        "type": "text/javascript",
                        "exec": [
                            'const original = pm.collectionVariables.get("userA") || "postman-demo-user";',
                            'pm.environment.set("userId", original);',
                            'pm.collectionVariables.set("userId", original);',
                            'console.log("Restored userId to " + original);',
                        ],
                    },
                },
                test_script(status_tests(200)),
            ],
            "request": {
                "method": "GET",
                "header": header_user(),
                "url": url("/api/transactions/{{transactionId}}"),
            },
            "response": [],
        },
    ],
    description=(
        "Creates a transaction as the collection userId, switches to postman-user-b (404), "
        "then restores the original user and verifies access. Restores userId at the end."
    ),
)

collection = {
    "info": {
        "_postman_id": COLLECTION_UID,
        "name": "Portfolio Service API",
        "description": (
            "Ready-to-run Postman collection for portfolio-service.\n\n"
            "## Quick start\n"
            "1. Start the service on port **8087** (default).\n"
            "2. Import `Portfolio-Service.postman_collection.json` and `Local.postman_environment.json`.\n"
            "3. Select the **Portfolio Service — Local** environment.\n"
            "4. Send any request in **01 - All Endpoints**, or run a **Runner** folder via Collection Runner.\n\n"
            "## Auth\n"
            "All `/api/*` calls require header `X-User-Id` (set from collection variable `userId`).\n\n"
            "## Variables\n"
            "- `baseUrl` — default `http://localhost:8087`\n"
            "- `userId` — demo user for scoping data\n"
            "- `ticker`, `fromDate`, `toDate` — shared filters\n"
            "- `transactionId` / `sellTransactionId` / `runId` — filled by create/trigger scripts\n\n"
            "## Runners\n"
            "Use Postman Collection Runner on folders A–E to simulate end-to-end test cases."
        ),
        "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json",
    },
    "variable": [
        var("baseUrl", "http://localhost:8087", "Service base URL"),
        var("userId", "postman-demo-user", "Value for X-User-Id header"),
        var("ticker", "AAPL", "Default ticker for path/query params"),
        var("fromDate", "2024-01-01", "Inclusive history start (YYYY-MM-DD)"),
        var("toDate", "2024-12-31", "Inclusive history end (YYYY-MM-DD)"),
        var("transactionId", "", "Populated by Create transaction scripts"),
        var("msftTransactionId", "", "Populated by BUY MSFT scripts"),
        var("sellTransactionId", "", "Populated by SELL scripts"),
        var("dividendTransactionId", "", "Populated by DIVIDEND scripts"),
        var("runId", "", "Populated by Trigger ingestion scripts"),
        var("userA", "postman-demo-user", "Used by Runner E to restore identity"),
    ],
    "event": [
        {
            "listen": "prerequest",
            "script": {
                "type": "text/javascript",
                "exec": [
                    "// Collection-level pre-request: nothing required.",
                    "// X-User-Id is set per request from {{userId}}.",
                ],
            },
        }
    ],
    "item": [
        all_endpoints,
        folder(
            "02 - Collection Runners (test cases)",
            [
                runner_crud,
                runner_buy_sell,
                runner_history,
                runner_auth,
                runner_isolation,
            ],
            description=(
                "Ordered scenarios for Postman Collection Runner / Newman. "
                "Run one folder at a time. Prefer a fresh userId (or empty DB) for Runner B."
            ),
        ),
    ],
}

environment = {
    "id": ENV_UID,
    "name": "Portfolio Service — Local",
    "values": [
        {"key": "baseUrl", "value": "http://localhost:8087", "type": "default", "enabled": True},
        {"key": "userId", "value": "postman-demo-user", "type": "default", "enabled": True},
        {"key": "ticker", "value": "AAPL", "type": "default", "enabled": True},
        {"key": "fromDate", "value": "2024-01-01", "type": "default", "enabled": True},
        {"key": "toDate", "value": "2024-12-31", "type": "default", "enabled": True},
    ],
    "_postman_variable_scope": "environment",
}


def main() -> None:
    collection_path = OUT_DIR / "Portfolio-Service.postman_collection.json"
    env_path = OUT_DIR / "Local.postman_environment.json"
    collection_path.write_text(json.dumps(collection, indent=2) + "\n")
    env_path.write_text(json.dumps(environment, indent=2) + "\n")
    print(f"Wrote {collection_path}")
    print(f"Wrote {env_path}")


if __name__ == "__main__":
    main()
