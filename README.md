# LedgerOne Backend

Java 25 / Spring Boot 4.1.1 REST API for the LedgerOne React app. This is an independent Maven and Git project. The matching project is `ledgerone-frontend`.

## Requirements

- JDK **25** (not just a JRE). Check `java -version` and `javac -version`.
- An internet connection for the first Maven dependency download. Maven itself is supplied through the official wrapper.

## Run in IntelliJ IDEA

1. Open this directory, or its `pom.xml`, as a Maven project.
2. Set **Project SDK** and **Maven runner JRE** to JDK 25. Use an IntelliJ version that supports Java 25.
3. Run the included **LedgerOne Backend** configuration, or open `src/main/java/com/ledgerone/LedgerOneApplication.java` and run its `main` method.
4. Keep the working directory set to this repository. The included run configuration does this and sets `LEDGER_DATA_DIR` to the repository's `data` directory.
5. The API starts at `http://127.0.0.1:8080`. Open `/api/health` to confirm it is running.
6. In the separate frontend repository, run `npm ci` then `npm run dev`. Open `http://localhost:5173`.

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
java -jar target/ledgerone-backend-1.0.0.jar
```

On Windows, use `.\mvnw.cmd` in place of `./mvnw`. If the wrapper loses its executable permission after extraction on macOS/Linux, run `chmod +x mvnw`.

## Run both apps together

Keep the directories beside each other:

```text
workspace/
  ledgerone-backend/
  ledgerone-frontend/
```

Run `npm ci` and then `npm run dev:all` from `ledgerone-frontend`. It starts the Maven backend, waits for its health check, then starts React. Ctrl+C stops both processes. Do not also start the IntelliJ backend on the same port; use frontend-only `npm run dev` when debugging Java separately.

## Annual XML storage

Each year is stored in **one XML file**:

```text
data/
  ledger-2026.xml
  ledger-2026.xml.bak
  ledger-2027.xml
  .ledger.lock
```

The `.bak` file is the previous successfully saved version, not another active ledger. The lock file coordinates writers. A temporary file exists only during a save. Every authoritative `ledger-YYYY.xml` contains twelve `<month>` sections with monthly budgets and transactions. XML does not have spreadsheet tabs: the React app displays the twelve sections as month tabs.

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

On the first run, if there are no year files in the data directory, the app seeds a clearly labelled sample ledger for the current year through today. It never reseeds an existing directory containing a year file.

For an empty personal ledger, set `LEDGER_SEED_DEMO=false` **before the first run in a new data directory**. Changing this flag does not remove existing sample or real data.

## Environment settings

| Variable | Default | Purpose |
| --- | --- | --- |
| `LEDGER_DATA_DIR` | `./data` | Ledger directory; use an absolute path to share it between IDE and terminal runs |
| `LEDGER_SEED_DEMO` | `true` | Seed sample data only when no annual files exist |
| `PORT` | `8080` | Backend port |
| `SERVER_ADDRESS` | `127.0.0.1` | Bind locally |
| `LEDGER_ALLOWED_ORIGINS` | localhost and 127.0.0.1 on ports 5173 and 4173 | Comma-separated exact frontend origins |

Spring reads environment variables, not an arbitrary `.env` file. Set them in your IDE run configuration or terminal. Never point multiple deployed replicas at different copies of the same logical ledger.

## API

See [API.md](API.md) for endpoints, categories, request examples, and error behavior.

## Scope

This is a local, single-user app with an enterprise visual design. It has no authentication, roles, bank integration, encryption at rest, or multi-tenant isolation. Keep it bound to loopback. Internet-facing or multi-user deployment needs an authentication and authorization layer and a production persistence design.

## Verification

`./mvnw verify` runs API integration and storage tests: exact decimal round trips, restart readback, XML escaping, corrupt-file preservation, DTD rejection, concurrent-write conflicts, month-to-month edits, deletion, input validation, budget persistence, and allowed/disallowed browser origins.

Framework compatibility: [Spring Boot requirements](https://docs.spring.io/spring-boot/system-requirements.html).
