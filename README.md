# Hisabo Backend

Java 25 / Spring Boot 4.1.1 REST API for the Hisabo React app. This is an independent Maven and Git project. The matching project is `hisabo-frontend`.

## Requirements

- JDK **25** (not just a JRE). Check `java -version` and `javac -version`.
- An internet connection for the first Maven dependency download. Maven itself is supplied through the official wrapper.

## Run in IntelliJ IDEA

1. Open this directory, or its `pom.xml`, as a Maven project.
2. Set **Project SDK** and **Maven runner JRE** to JDK 25. Use an IntelliJ version that supports Java 25.
3. Run the included **Hisabo Backend** configuration, or open `src/main/java/com/hisabo/HisaboApplication.java` and run its `main` method.
4. Keep the working directory set to this repository. The included run configuration does this and sets `LEDGER_DATA_DIR` to the repository's `data` directory.
5. The API starts at `http://127.0.0.1:8080`. Open `/api/health` to confirm it is running.
6. In the separate frontend repository, run `npm ci` then `npm run dev`. Open `http://hisabo.localhost:5173`.

You can set breakpoints in the controller, service, or XML store and run **Debug** in IntelliJ. Keep the frontend running in VS Code.

## Terminal

macOS / Linux:

```sh
./mvnw spring-boot:run
```

Windows PowerShell:

```powershell
.\mvnw.cmd spring-boot:run
```

Build, test, and run a packaged app:

```sh
./mvnw verify
java -jar target/hisabo-backend-1.0.0.jar
```

On Windows, use `.\mvnw.cmd` in place of `./mvnw`. If the wrapper loses its executable permission after extraction on macOS/Linux, run `chmod +x mvnw`.

## Run both apps together

Keep the directories beside each other:

```text
workspace/
  hisabo-backend/
  hisabo-frontend/
```

Run `npm ci` and then `npm run dev:all` from `hisabo-frontend`. It starts the Maven backend, waits for its health check, then starts React. Ctrl+C stops both processes. Do not also start the IntelliJ backend on the same port; use frontend-only `npm run dev` when debugging Java separately.

## Profiles and Excel workbooks

Use **Manage profiles** in the sidebar, or your profile name at the top right, to create, switch, or rename profiles. There is no fixed profile limit. Each starts with empty transactions and zero budgets. Existing ledgers appear under **Personal**; you can rename it without moving or rewriting existing XML. Profile names are unique ignoring case, may include Unicode, and are 1–60 characters long.

Every profile has one Excel workbook per year, named for example `Ronak-2026.xlsx`, with an Overview and January–December sheets. The Java backend creates it when that year is opened and updates it after transaction or budget changes. **Download Excel** saves a copy through the browser. Names unsafe for filenames are replaced with hyphens. Renaming a profile updates its generated workbook names while retaining its stable internal ID and data.

XML remains the authoritative storage. Excel is a generated export: edits in Excel are not imported and will be overwritten on the next refresh. If Excel is open and your OS prevents replacement, the app confirms the XML save and shows a workbook warning. Close Excel and use Refresh or Download Excel to retry; do not re-add an already saved transaction.

Default layout inside `LEDGER_DATA_DIR`:

```text
data/
  profiles.properties                  # Profile IDs and display names
  profiles.properties.bak              # Previous profile list
  ledger-2026.xml                       # Existing Personal profile, preserved in place
  excel/Personal-2026.xlsx
  profiles/<profile-id>/
    ledger-2026.xml                     # This profile's transactions and budgets
    ledger-2026.xml.bak
    excel/Ronak-2026.xlsx
```

These are local budget profiles, not password-protected accounts. Everyone using this local app can switch between them. Copy the entire data directory, including the profile list, when moving or backing up the app. All runtime XML and Excel files remain excluded from Git.

After upgrading: reload the Maven project in IntelliJ to fetch the [Apache POI Excel dependency](https://poi.apache.org/), restart **Hisabo Backend**, and refresh the frontend. Node setup and `npm run dev` are unchanged.

## Annual XML storage

Each year is stored in **one XML file**:

```text
data/
  ledger-2026.xml
  ledger-2026.xml.bak
  ledger-2027.xml
  .ledger.lock
```

The `.bak` file is the previous successfully saved version, not another active ledger. The lock file coordinates writers. A temporary file exists only during a save. For each profile, every authoritative `ledger-YYYY.xml` contains twelve `<month>` sections with monthly budgets and transactions. XML does not have spreadsheet tabs: the React app displays the twelve sections as month tabs.

Excerpt of the format (a real file contains all twelve months and all six expense budgets per month):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<ledger schemaVersion="1" year="2026" currency="USD" revision="2" demo="false">
  <month number="9" name="SEPTEMBER">
    <budgets>
      <budget category="FOOD" amount="850.00" />
      <!-- HOUSING, TRANSPORT, SHOPPING, LIFESTYLE, UTILITIES also appear -->
    </budgets>
    <transactions>
      <transaction id="d2bb5699-346a-4c16-b7b7-3f46f514aa33" type="EXPENSE"
        category="FOOD" account="CHECKING" amount="12.34" date="2026-09-27"
        createdAt="2026-09-27T12:00:00Z" updatedAt="2026-09-27T12:00:00Z">
        <description>Lunch</description>
      </transaction>
    </transactions>
  </month>
</ledger>
```

- All reads load the current file; transactions do not depend on browser storage or a server memory cache.
- Writes use `BigDecimal` amounts, a process lock, an OS file lock, a flushed temporary file, and an atomic replacement.
- A revision number rejects stale writes with HTTP 409. The frontend reloads the current revision and keeps the unsaved form open.
- A bad XML file is preserved and reported as an error; it is never silently replaced with empty data.
- External XML entities and DTDs are disabled.
- Transaction IDs remain stable when an edit moves a transaction between months within the same year. Moving across years is intentionally rejected.
- Years 2000–2100 are accepted. Amounts use USD with up to two decimal places.

Stop the backend before editing XML by hand. Keep all required sections, increment the revision, and restart afterward. To recover a backup: stop the backend, copy the damaged file somewhere safe, copy `ledger-YYYY.xml.bak` over `ledger-YYYY.xml`, and restart.

**Back up the `data` directory yourself.** A single previous-version backup does not protect against disk loss. The runtime data directory is excluded from Git so financial transactions are not pushed with source code.

## Sample data or an empty ledger

New data directories start with an empty personal ledger by default. Your first saved transaction or budget creates that year's XML file.

To try sample data, explicitly set `LEDGER_SEED_DEMO=true` before starting with a new, empty data directory. The app seeds a clearly labelled sample ledger for the current year through today. It never reseeds an existing directory containing a year file. Changing this flag does not remove existing sample or real data.

## Environment settings

| Variable | Default | Purpose |
| --- | --- | --- |
| `LEDGER_DATA_DIR` | `./data` | Ledger directory; use an absolute path to share it between IDE and terminal runs |
| `LEDGER_SEED_DEMO` | `false` | Opt in to sample data only when no annual files exist |
| `PORT` | `8080` | Backend port |
| `SERVER_ADDRESS` | `127.0.0.1` | Bind locally |
| `LEDGER_ALLOWED_ORIGINS` | hisabo.localhost, localhost, and 127.0.0.1 on ports 5173 and 4173 | Comma-separated exact frontend origins |

Spring reads environment variables, not an arbitrary `.env` file. Set them in your IDE run configuration or terminal. Never point multiple deployed replicas at different copies of the same logical ledger.

## API

See [API.md](API.md) for endpoints, categories, request examples, and error behavior.

## Scope

This is a local, single-user app with an enterprise visual design. It has no authentication, roles, bank integration, encryption at rest, or multi-tenant isolation. Keep it bound to loopback. Internet-facing or multi-user deployment needs an authentication and authorization layer and a production persistence design.

## Verification

`./mvnw verify` runs API integration and storage tests: exact decimal round trips, restart readback, XML escaping, corrupt-file preservation, DTD rejection, concurrent-write conflicts, month-to-month edits, deletion, input validation, budget persistence, allowed/disallowed browser origins, profile isolation and restart persistence, profile rename, concurrent creation, Excel monthly formulas, safe text cells, and recovery from Excel export failures.

Framework compatibility: [Spring Boot requirements](https://docs.spring.io/spring-boot/system-requirements.html).


## Optional Home & mortgage template

When creating a profile, choose **Home & mortgage** to add a mortgage planner. Blank budget remains the default. A mortgage profile still has its ordinary monthly budget workspace, with independent transactions and annual Excel files.

The mortgage planner asks for the original start date, the principal balance **immediately after a regular monthly payment**, that balance date, the nominal annual loan interest rate (not APR), and either the monthly principal-and-interest payment or the remaining amortization term in months. Optional monthly taxes and insurance are displayed separately. Choose annual-rate/12 monthly interest, or the Canadian nominal semiannual convention `(1 + annualRate/200)^(1/6) - 1` per month. Both options assume a fixed rate and monthly payments for the projection.

Compare a baseline schedule with extra principal each month (starting in a chosen month), multiple dated one-time principal payments, or both. Each one-time entry has a stable ID, full date, and positive amount; entries can be edited or removed. Dates must be after the balance snapshot and within the next 600 months, with at most 500 entries. Do not add payments already reflected in the snapshot. Preview does not save. **Save plan** persists the entire list and refreshes Excel.

Dates are anchored to the balance date: each due date is `balanceDate.plusMonths(paymentNumber)`, preserving the original day when a later month allows it. The monthly model applies each dated payment on the first scheduled due date on or after its entered date. For example, with a due day of the 15th, an entry dated March 16 is modeled on April 15. The UI and workbook show both dates. The regular payment and monthly extra are applied first, followed by one-time entries in date order (input order for the same date). Principal is capped at the amount still owed; later entries are marked not applied after payoff. Interest and payments use cents, rounded half up. A payment calculated from a remaining term has its last payment adjusted for rounding. A stated payment that cannot amortize within 600 months is rejected.

Results include the baseline and accelerated payoff dates, remaining payments, future interest, interest saved, months saved, a balance chart, and both detailed amortization schedules. The one-time payment impact separately compares against the same plan with monthly extras but without one-time entries, showing additional interest/months saved, applied principal, and projected balance after each entry. The interest rate itself does not change. Results begin at the balance snapshot; historical interest is not reconstructed from the original start date. Rate resets, refinancing, daily interest, partial-month interest, fees, and prepayment penalties are not modeled. Canadian fixed-rate projections hold the entered rate constant beyond renewal. Verify assumptions and any prepayment limits against the loan agreement.

Home improvement expenses include a date, project, description, and amount, with edit/delete, year filtering, and totals. They are tracked independently; they do not automatically increase mortgage principal or create regular budget transactions.

**Monthly mortgage overview** lets you match the escrow amount on a statement without unlocking the loan details. Pick a year and quarter (or **Full year**), then select an escrow amount to open its inline editor. The **Balance** column shows the principal remaining after that payment and any extras. **Yearly totals** switches the same compact panel to annual projections; select a year there to return to its monthly details. This replaces the separate Payment by payment section. Changes default to **This month onward**, carrying into following months and years until the next carry-forward change. **This month only** overrides just that month; the prior ongoing amount resumes afterward. Reset removes an entry and restores the previous carried amount or the loan's default. Zero is a valid amount. **Calculate** previews the changes; **Save plan & escrow** saves the entire mortgage plan and refreshes its workbook.

Monthly housing projections include principal and interest, extra principal, and the effective escrow amount. The section also shows calendar-year totals and projected escrow through payoff. Totals start with the first payment after the balance snapshot and end at the accelerated payoff; they are projections, not a history of paid statements. Escrow never changes amortization, interest savings, or payoff dates. Taxes and insurance can continue after the loan is paid off. Saved escrow changes remain editable while the loan details are locked.

For a mortgage profile, `data/profiles/<profile-id>/mortgage.xml` stores the loan snapshot, scenario, and improvement history. Mortgage XML schema 3 stores the dated payment list and monthly escrow changes. Schemas 1 and 2 remain readable and keep their default escrow amount until a change is entered; they become schema 3 on the next save. Schema 1 month-only principal payments are read as a dated entry on the original month's scheduled due date, preserving their previous projection. It uses the same workspace locking, revision conflict handling, atomic writes, previous-version backup, and strict XML parsing as the profile workflow. The separate `<profile name>-mortgage.xlsx` workbook is saved in that profile's `excel` folder. It includes eight sheets once configured: Mortgage overview, Standard schedule, Extra principal schedule, One-time payments, Monthly housing payments, Yearly housing totals, Escrow changes, and Home improvements. It spans the full remaining loan, while the ordinary budget workbooks remain yearly. Excel is an export; edit the plan in Hisabo. An export failure reports a warning after a successful XML save.

Mortgage checks cover exact hand-calculated schedules, zero interest, delayed and oversized extras, final-payment caps, month-end dates, a known 30-year payment, Canadian interest, stale revisions, XML persistence and corruption, profile isolation, expense editing, preview without saving, workbook contents, and HTTP validation.

Calculation context: [CFPB amortization explanation](https://www.consumerfinance.gov/ask-cfpb/how-does-paying-down-a-mortgage-work-en-1943/) and [CFPB prepayment penalties](https://www.consumerfinance.gov/ask-cfpb/what-is-a-prepayment-penalty-en-1957/).

### Configurable budget categories

Each profile stores its category names, colors, active/removed status, and configuration revision in `budget-categories.xml`, beside that profile's annual ledgers. Existing installations automatically use the six original categories until settings are saved. Configuration applies across years within that profile; IDs remain stable through renames and removal.

Removing a category is reversible. Existing transactions and monthly budgets are preserved, including their amounts in reports and Excel. New expenses must use an active category; an existing expense can still be edited without changing its removed category. Custom categories start at zero until a monthly budget is entered. XML ledger schema 2 supports custom string category IDs; schema 1 ledgers are still readable and are upgraded only on their next ledger write. Use the updated backend when reading schema 2 files, and back up `budget-categories.xml` together with the annual XML files.

Settings use the same profile/process lock as ledger operations, revision conflict checks, atomic file replacement, and a `.bak` copy. All previously exported/saved years are refreshed in Excel when categories change; export failures are reported without hiding successfully saved XML settings. Workbook row positions adapt to the category count. Names support 1–40 characters and must be unique within a profile; the limit is 200 entries including removed categories.

### Home sale estimates

Mortgage profiles can save a home sale scenario using `PUT /api/profiles/{profileId}/mortgage/home-sale`. The estimate deducts the mortgage payoff, agent fees, closing costs, buyer credits, other loans, and an optional manually entered tax reserve. It uses the saved accelerated mortgage schedule through the closing date unless a lender quote overrides the principal. Daily payoff interest, lender fees, and escrow refunds are not inferred; capital-gains tax is not calculated.

Settings are stored in an optional `homeSale` element in each profile’s `mortgage.xml`, protected by the existing revision, lock, backup, and atomic-write mechanism. Schema 4 reads older mortgage schemas 1–3. Other mortgage edits retain sale settings. Saved scenarios add a **Home sale estimate** Excel sheet with formulas. If a later balance date makes the sale date obsolete, inputs are retained and the estimate stays unavailable until the closing date is corrected. Use the matching frontend and restart Java after updating.

### Savings and repeating budgets

Budget performance shows **Savings** as `max(0, recorded monthly income − category allocations)`, with a separate shortfall when allocations exceed income. This is a planning amount, not a savings-account transfer or an expense; transactions remain unchanged. The API and Excel workbook expose the same figures separately from actual net cash flow.

A budget saved for a month becomes the plan for later unconfigured months, including January of the next year. Editing a carried plan creates a manual override; an explicitly saved zero budget also overrides inheritance. Existing manual plans remain intact. Changes to the source plan flow into inherited months when read; the ledger persists any updated copies atomically, increments its revision, and creates a `.bak`. Repeated reads are idempotent. Future years materialize and export when opened or downloaded.

XML schema 3 records `budgetOrigin` (`UNSET`, `MANUAL`, `CARRIED`) and the original manual plan's `budgetSource` (`YYYY-MM`). Schemas 1 and 2 remain readable: nonzero legacy budgets count as manual plans, while all-zero legacy months count as unconfigured because the old format did not distinguish an explicit zero plan. Removed categories are not added to new inherited months; amounts already present in existing months are preserved. Keep yearly XML files and the category catalog together when backing up the profile.
