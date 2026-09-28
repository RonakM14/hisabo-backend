# Hisabo API

Base URL: `http://127.0.0.1:8080/api`. Requests and responses use JSON; the Java backend persists data as XML. Dates are `YYYY-MM-DD`; timestamps are ISO-8601 UTC.

| Method | Path | Result |
| --- | --- | --- |
| GET | `/health` | Application health |
| GET | `/years` | Stored years plus the current year |
| GET | `/years/{year}` | Currency, revision, demo flag, all twelve monthly budgets and transaction lists |
| POST | `/years/{year}/transactions` | Add a transaction; return the updated full year |
| PUT | `/years/{year}/transactions/{id}` | Update a transaction; return the updated full year |
| DELETE | `/years/{year}/transactions/{id}?revision={n}` | Delete; return the updated full year |
| PUT | `/years/{year}/months/{month}/budgets` | Replace all six monthly budgets; return the updated full year |

Read the year first and send its current revision for every mutation. An unknown year returns a twelve-month empty ledger at revision 0 without creating a file. The first successful write creates its XML file.

## Transaction request

```json
{
  "revision": 0,
  "type": "EXPENSE",
  "description": "Weekly groceries",
  "amount": 78.25,
  "category": "FOOD",
  "account": "CREDIT",
  "date": "2026-09-27"
}
```

Expense categories: `HOUSING`, `FOOD`, `TRANSPORT`, `SHOPPING`, `LIFESTYLE`, `UTILITIES`.

Income categories: `SALARY`, `FREELANCE`, `OTHER_INCOME`.

Accounts: `CHECKING`, `CREDIT`, `CASH`, `SAVINGS`. Category and type must agree. Amounts are 0.01–10,000,000.00, with at most two decimal places. Description is 1–120 characters and cannot contain control characters. Date must be in the URL's year.

## Budget request

```json
{
  "revision": 1,
  "budgets": {
    "HOUSING": 1800,
    "FOOD": 850,
    "TRANSPORT": 500,
    "SHOPPING": 650,
    "LIFESTYLE": 450,
    "UTILITIES": 350
  }
}
```

All six expense categories are required. Values may be zero and have at most two decimal places.

## Errors

- `400`: invalid body, amount, date, category, month, or year.
- `403`: browser origin is not allowed.
- `404`: transaction does not exist.
- `409`: stale revision. Read the year again, review the change, and resubmit with the current revision.
- `500`: storage read or save failed. Existing files are preserved whenever a replacement cannot be completed.

Validation and storage errors use Spring's Problem Detail JSON with `status` and `detail`. Do not blindly retry a timed-out write: reload the year first to see whether it was saved.
