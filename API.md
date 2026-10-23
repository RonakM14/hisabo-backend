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
| PUT | `/years/{year}/months/{month}/budgets` | Save monthly category budgets; return the updated full year |

Read the year first and send its current revision for every mutation. An unknown year with no earlier budget plan returns a twelve-month empty ledger at revision 0 without creating a file. If an earlier plan exists, reading the year copies its budgets and creates the year's XML file. Reads that refresh inherited budgets may also increment the revision.

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

Default expense categories: `HOUSING`, `FOOD`, `TRANSPORT`, `SHOPPING`, `LIFESTYLE`, `UTILITIES`. Profiles can add or remove categories; use the profile's current category catalog.

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

All active expense categories are required. Known removed categories may be included; omitting them preserves their saved allocations. Values may be zero and have at most two decimal places.

## Errors

- `400`: invalid body, amount, date, category, month, or year.
- `403`: browser origin is not allowed.
- `404`: transaction does not exist.
- `409`: stale revision. Read the year again, review the change, and resubmit with the current revision.
- `500`: storage read or save failed. Existing files are preserved whenever a replacement cannot be completed.

Validation and storage errors use Spring's Problem Detail JSON with `status` and `detail`. Do not blindly retry a timed-out write: reload the year first to see whether it was saved.


## Named profiles

- `GET /api/profiles` → array of `{id, name, workbookWarning}`.
- `POST /api/profiles` with `{"name":"Ronak"}` → newly created, empty profile.
- `PUT /api/profiles/{profileId}` with `{"name":"Family"}` → rename without changing the ID.
- `GET /api/profiles/{profileId}/years` → available years and currency.
- `GET /api/profiles/{profileId}/years/{year}` → annual ledger plus `workbook: {fileName, warning}`.
- Use the same transaction and budget paths documented above under `/api/profiles/{profileId}/years/{year}` for profile-specific writes. Revisions are independent per profile and year.
- `GET /api/profiles/{profileId}/years/{year}/excel` → freshly generated `.xlsx` attachment, with an Overview and 12 monthly sheets.

Legacy `/api/years/...` paths continue to address the original Personal profile (ID `default`), including after it is renamed. New clients should always send the explicit profile ID. Unknown profiles return 404. Invalid names return 400; duplicate names return 409. Empty names, names over 60 characters, and control characters are rejected. Profile names are normalized and compared without case sensitivity.

An Excel export error never hides a successful XML transaction save: the annual response still succeeds with a non-null `workbook.warning`. Profile creation or rename may similarly return `workbookWarning`. Refresh or download retries the export. Excel downloads can return 500 if writing the workbook fails. Profile operations use a shared process and OS lock to prevent lost profile-list updates or old exports replacing new exports.


## Mortgage profile template

`POST /api/profiles` now accepts optional `template: "BUDGET" | "MORTGAGE"` (default `BUDGET`). Profile responses include `template`; existing profiles remain budgets.

Under `/api/profiles/{profileId}/mortgage`:

- `GET /` returns `{revision, loan, scenario, improvements, analysis, fileName, workbookWarning, supportsDatedPayments: true, supportsMonthlyEscrow: true}`; a new template has `loan: null`.
- `POST /preview` accepts `{loan, scenario}` and returns an analysis without changing the stored plan or workbook.
- `PUT /` accepts `{revision, loan, scenario}` and saves the plan.
- `POST /improvements` accepts `{revision, date, project, description, amount}`.
- `PUT /improvements/{id}` accepts the same body to edit an expense.
- `DELETE /improvements/{id}?revision=N` deletes an expense.
- `GET /excel` downloads the latest saved mortgage workbook.
- `PUT /home-sale` accepts `{revision, homeSale}` and returns the updated mortgage view. Requires saved loan details. Other mortgage writes preserve the sale settings.

`homeSale` contains `salePrice` (positive USD), `closingDate` (ISO date on/after the saved balance date, through 2100), nullable `payoffOverride` (a lender quote; zero is valid), `agentFeePercent` (0–100), `closingCosts`, `buyerCredits`, `otherLoans`, `taxEnabled`, and `taxReserve`. All amounts are nonnegative USD up to 100,000,000 with at most two decimal places; the percentage also allows two decimals. The reserve must be supplied even when disabled, and is deducted only when `taxEnabled` is true. This is a manually entered reserve, not a tax calculation.

Views also expose `supportsHomeSale: true`, nullable `homeSale`, and nullable `homeSaleEstimate`: `{projectedBalance, balanceAsOf, mortgagePayoff, agentFee, sellingCosts, cashBeforeTax, taxReserve, cashInHand}`. Projected principal uses the saved accelerated schedule, after all payments due on/before closing; a quote overrides it. Daily payoff interest, lender fees, and escrow refunds are not inferred. Agent fees round to cents; selling costs include agent fees, other closing costs, and buyer credits. Other secured loans are deducted separately. Negative cash is a shortfall, not clamped to zero. If a later loan snapshot moves beyond the saved closing date, inputs are retained but the estimate is null until corrected. Saved estimates add a `Home sale estimate` sheet with formulas to that profile’s mortgage workbook.

Loan fields: `startDate`, `balanceDate` (ISO dates), `balance`, `annualRate` (percent), `interestConvention` (`MONTHLY` or `CANADIAN_SEMIANNUAL`), `paymentMode` (`PAYMENT` or `TERM`), `monthlyPayment` (required for PAYMENT), `remainingMonths` (required for TERM, 1–600), `monthlyEscrow` (use 0 if excluded). Scenario fields: `monthlyExtra`, `extraStartMonth` (YYYY-MM), and `oneTimePayments` (the entire list, or `[]` to remove all). Positive monthly extras require a valid month on or after the first scheduled payment. Use 0 for unused monthly extras and null for their start month.

Each `oneTimePayments` entry is `{id: UUID, date: "YYYY-MM-DD", amount: decimal}`. IDs must be unique within the plan; amounts must be positive with at most two decimals; dates must be strictly after `balanceDate`, within 600 months, and between 1900 and 2100. Maximum 500 entries. Same-date payments remain separate and follow input order. Legacy `oneTimeExtra` and `oneTimeMonth` are still accepted and converted to a dated entry on that month's original due date. New clients should omit them or send 0/null. Saved responses return the canonical dated list.

The existing monthly calculation applies each entry on the first scheduled due date on or after its entered date, after the regular payment and monthly extra. `analysis.oneTimeImpact` includes `requested`, `applied`, `interestSaved`, `monthsSaved`, and `payments`. These savings compare with the same monthly-extra plan without one-time payments. Each result includes `id`, `date`, `applicationDate`, `requested`, `applied`, `balanceAfter`, and `status` (`APPLIED`, `PARTIALLY_APPLIED`, or `NOT_APPLIED`). Final principal is capped; payments after payoff remain in the list with zero applied. The annual rate does not change. These are projections from the supplied balance; no budget transaction is created.

`scenario.escrowChanges` is a list of `{month: "YYYY-MM", amount: decimal, scope: "FROM_MONTH" | "THIS_MONTH"}`. Months must be unique, between 1900-01 and 2100-12; amounts range from 0 to 1,000,000 with at most two decimals; maximum 1,200 entries. `FROM_MONTH` carries forward until the next `FROM_MONTH` entry. `THIS_MONTH` wins for its own month only, then the previous carry-forward amount resumes. Before any applicable entry, use `loan.monthlyEscrow` (also limited to 1,000,000). Explicit `[]` resets the schedule. Omitted or null `escrowChanges` preserves the saved schedule for older clients on preview and save. Saved responses return the list sorted by month.

`analysis.housing` contains `escrowTotal`, `totalPayments`, `months`, and `years`. Each monthly row includes `date`, `principalAndInterest`, `extraPrincipal`, `escrow`, `totalPayment`, `balance` (principal remaining after that payment and all extras), and nullable `escrowSourceMonth`/`escrowScope`. Each yearly row includes `year`, `payments` (count), `principalAndInterest`, `extraPrincipal`, `escrow`, and `totalPayment`. Rows follow the accelerated remaining schedule only; no past payment history is inferred. `monthlyHousingPayment` is the first projected regular principal-and-interest payment plus its escrow, excluding extra principal (zero when already paid off). Escrow does not change any principal, interest, savings, or payoff calculation. The XML format is mortgage schema 4, with schema 1/2/3 read compatibility. An optional `homeSale` element stores sale settings; older files remain unchanged until saved.

All amounts are nonnegative decimal currency values with at most two decimals (expenses and stated payments must be positive); the annual interest rate is 0–30 percent. The mortgage revision is independent of the annual ledger revision. Conflicts return 409. A normal budget profile has no mortgage document; mortgage operations on it return 400. Unknown profiles return 404. Mortgage financial projections are available only for fixed-rate loans with regular monthly periods.

## Budget categories

`GET /api/profiles/{profileId}/years/{year}` now includes `categories: { revision, entries }`. Each entry has a stable `id`, a `label`, a six-digit hex `color`, and an `active` boolean. An absent category settings file yields the six original defaults at configuration revision 0. Category revisions are independent of annual ledger revisions.

`PUT /api/profiles/{profileId}/budget-categories?year=2026` accepts the full category catalog and its expected revision, and returns the requested `ProfileYear` with updated settings and workbook status:

```json
{"revision":0,"entries":[{"id":"HOUSING","label":"Housing","color":"#4263eb","active":true}]}
```

The example shows one entry's shape: a real update must include **every existing entry**, including removed ones. Set `active:false` to remove an entry; `active:true` restores it. Append new entries with IDs of the form `CUSTOM_<lowercase UUID>`. Existing default IDs remain valid. Duplicate IDs/names, invalid colors, dropped existing entries, and more than 200 entries are rejected. A stale settings revision returns 409. Names are normalized with NFKC, trimmed, and limited to 40 characters.

Transaction category fields and budget map keys accept these IDs. New expenses reject removed or unknown categories; edits may retain their original removed category. Monthly budget updates must include all currently active categories and may include known removed categories to edit historical allocations. Omitting a removed category preserves its saved budget. Unknown categories cannot cross profile boundaries. Excel exports resolve names from the profile catalog and retain historical rows and totals for removed categories.

## Savings and budget carry-forward

Each month now includes `budgetOrigin`, nullable `budgetSource`, and `budgetSummary: { income, allocated, savings, shortfall }`. Savings is the nonnegative difference between recorded income and total category budgets; shortfall is the opposite nonnegative difference. These values do not create transactions or alter actual cash flow.

Saving monthly budgets marks that month `MANUAL`. Later months without manual plans inherit it as `CARRIED`, including across years. Saving an inherited month (even with all zero values) makes an independent manual plan. Reads may materialize or refresh inherited amounts into XML and increment that year's revision; submit the revision from the latest response. Stale writes return 409. The source is the original manual month, not a transaction date. Budget inheritance remains scoped to the profile.
