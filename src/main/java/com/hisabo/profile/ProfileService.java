package com.hisabo.profile;

import static java.nio.file.StandardOpenOption.*;

import com.hisabo.domain.Ledger;
import com.hisabo.domain.Ledger.*;
import com.hisabo.mortgage.Mortgage;
import com.hisabo.mortgage.MortgageStore;
import com.hisabo.mortgage.MortgageWorkbook;
import com.hisabo.service.LedgerService;
import com.hisabo.service.LedgerService.NotFoundException;
import com.hisabo.storage.XmlLedgerStore;
import com.hisabo.storage.XmlLedgerStore.*;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ProfileService {
  public enum Template {
    BUDGET,
    MORTGAGE
  }

  public record Profile(String id, String name, String workbookWarning, Template template) {}

  public record WorkbookInfo(String fileName, String warning) {}

  public record ProfileYear(
      int year,
      String currency,
      long revision,
      boolean demo,
      List<Month> months,
      WorkbookInfo workbook,
      BudgetCategories.Catalog categories) {}

  public record Download(String fileName, byte[] bytes) {}

  private final Path root;
  private final ExcelLedgerWriter excel;
  private final Map<String, XmlLedgerStore> stores = new ConcurrentHashMap<>();
  // Shared by instances as well as threads; the OS lock coordinates separate Java processes.
  private static final Map<Path, ReentrantLock> MUTEXES = new ConcurrentHashMap<>();
  private final ReentrantLock mutex;

  public ProfileService(
      @Value("${ledger.data-dir}") String directory,
      XmlLedgerStore defaultStore,
      ExcelLedgerWriter excel) {
    root = Path.of(directory).toAbsolutePath().normalize();
    stores.put("default", defaultStore);
    this.excel = excel;
    mutex = MUTEXES.computeIfAbsent(root, ignored -> new ReentrantLock(true));
  }

  public List<Profile> profiles() {
    return locked(
        () -> {
          Properties catalog = catalog();
          return catalog.stringPropertyNames().stream()
              .map(id -> new Profile(id, catalog.getProperty(id), null, template(id)))
              .sorted(
                  Comparator.comparing((Profile p) -> !p.id().equals("default"))
                      .thenComparing(Profile::name, String.CASE_INSENSITIVE_ORDER))
              .toList();
        });
  }

  public Profile create(String input) {
    return create(input, Template.BUDGET);
  }

  public Profile create(String input, Template selectedTemplate) {
    Template template = selectedTemplate == null ? Template.BUDGET : selectedTemplate;
    return locked(
        () -> {
          Properties catalog = catalog();
          String name = validateName(input, catalog, null);
          String id = UUID.randomUUID().toString();
          XmlLedgerStore store = store(id);
          store.seedIfEmpty(Ledger.empty(LocalDate.now().getYear()));
          if (template == Template.MORTGAGE)
            new MortgageStore().write(directory(id), Mortgage.Plan.empty());
          catalog.setProperty(id, name);
          saveCatalog(catalog);
          String warning = export(id, name, store.read(LocalDate.now().getYear())).warning();
          if (template == Template.MORTGAGE) {
            String mortgageWarning = exportMortgage(id, name, null);
            if (mortgageWarning != null) warning = mortgageWarning;
          }
          return new Profile(id, name, warning, template);
        });
  }

  public Profile rename(String id, String input) {
    return locked(
        () -> {
          Properties catalog = catalog();
          String previousName = requireProfile(catalog, id);
          String name = validateName(input, catalog, id);
          var ledgers = yearsUnlocked(id).stream().map(year -> store(id).read(year)).toList();
          catalog.setProperty(id, name);
          saveCatalog(catalog);
          String warning = null;
          for (Year ledger : ledgers) {
            int year = ledger.year();
            WorkbookInfo info = export(id, name, ledger);
            if (info.warning() != null) warning = info.warning();
            else if (!excel.fileName(previousName, year).equals(info.fileName())) {
              Path old = directory(id).resolve("excel").resolve(excel.fileName(previousName, year));
              Path current = directory(id).resolve("excel").resolve(info.fileName());
              // Case-insensitive filesystems may resolve old and new names to the same file.
              try {
                if (Files.exists(old) && !Files.isSameFile(old, current)) Files.delete(old);
              } catch (IOException e) {
                warning =
                    "Profile renamed. An older Excel copy could not be removed; close Excel and"
                        + " remove that old copy if needed.";
              }
            }
          }
          if (template(id) == Template.MORTGAGE) {
            String mortgageWarning = exportMortgage(id, name, previousName);
            if (mortgageWarning != null) warning = mortgageWarning;
          }
          return new Profile(id, name, warning, template(id));
        });
  }

  public Map<String, Object> years(String id) {
    return locked(
        () -> {
          requireProfile(catalog(), id);
          return Map.of("years", yearsUnlocked(id), "currency", "USD");
        });
  }

  private SortedSet<Integer> yearsUnlocked(String id) {
    var years = new TreeSet<Integer>(Comparator.reverseOrder());
    years.addAll(store(id).years());
    years.add(LocalDate.now().getYear());
    Path exports = directory(id).resolve("excel");
    if (Files.isDirectory(exports)) {
      try (var files = Files.list(exports)) {
        files
            .map(p -> p.getFileName().toString())
            .filter(n -> n.matches(".+-[0-9]{4}\\.xlsx"))
            .map(n -> Integer.parseInt(n.substring(n.length() - 9, n.length() - 5)))
            .filter(y -> y >= 2000 && y <= 2100)
            .forEach(years::add);
      } catch (IOException e) {
        throw new StorageException("Could not read this profile's workbook list.", e);
      }
    }
    return years;
  }

  public ProfileYear year(String id, int year) {
    return change(id, year, service -> store(id).read(year));
  }

  public ProfileYear add(String id, int year, TransactionInput input) {
    return change(id, year, service -> service.add(year, input));
  }

  public ProfileYear edit(String id, int year, UUID transaction, TransactionInput input) {
    return change(id, year, service -> service.edit(year, transaction, input));
  }

  public ProfileYear delete(String id, int year, UUID transaction, long revision) {
    return change(id, year, service -> service.delete(year, transaction, revision));
  }

  public ProfileYear budgets(String id, int year, int month, BudgetInput input) {
    return change(id, year, service -> service.budgets(year, month, input));
  }

  private ProfileYear change(String id, int year, Function<LedgerService, Year> action) {
    return locked(
        () -> {
          String name = requireProfile(catalog(), id);
          var categories = new BudgetCategories().read(directory(id));
          validateCategoryReferences(store(id).read(year), categories);
          Year result = action.apply(new LedgerService(store(id), categories));
          validateCategoryReferences(result, categories);
          WorkbookInfo workbook = export(id, name, result);
          return new ProfileYear(
              result.year(),
              result.currency(),
              result.revision(),
              result.demo(),
              result.months(),
              workbook,
              categories);
        });
  }

  public ProfileYear categories(String id, int year, BudgetCategories.Input input) {
    XmlLedgerStore.checkYear(year);
    return locked(
        () -> {
          String name = requireProfile(catalog(), id);
          // Verify source ledgers before changing settings; never discard unreadable history.
          var years = new TreeSet<>(yearsUnlocked(id));
          years.add(year);
          var ledgers = years.stream().map(y -> store(id).read(y)).toList();
          var previous = new BudgetCategories().read(directory(id));
          ledgers.forEach(l -> validateCategoryReferences(l, previous));
          var categories = new BudgetCategories().save(directory(id), input);
          String warning = null;
          for (Year ledger : ledgers) {
            var info = export(id, name, store(id).read(ledger.year()));
            if (info.warning() != null) warning = info.warning();
          }
          var current = store(id).read(year);
          return new ProfileYear(
              year,
              current.currency(),
              current.revision(),
              current.demo(),
              current.months(),
              new WorkbookInfo(excel.fileName(name, year), warning),
              categories);
        });
  }

  private void validateCategoryReferences(Year year, BudgetCategories.Catalog categories) {
    var known =
        categories.entries().stream()
            .map(BudgetCategories.Entry::id)
            .collect(java.util.stream.Collectors.toSet());
    for (Month month : year.months()) {
      if (!known.containsAll(month.budgets().keySet())
          || month.transactions().stream()
              .anyMatch(t -> t.type() == Type.EXPENSE && !known.contains(t.category())))
        throw new StorageException(
            "This ledger references missing budget categories. Restore its budget-categories.xml"
                + " file; your ledger is preserved.",
            null);
    }
  }

  public Download download(String id, int year) {
    return locked(
        () -> {
          String name = requireProfile(catalog(), id);
          try {
            var categories = new BudgetCategories().read(directory(id));
            var ledger = store(id).read(year);
            validateCategoryReferences(ledger, categories);
            Path file = excel.write(directory(id).resolve("excel"), name, ledger, categories);
            return new Download(file.getFileName().toString(), Files.readAllBytes(file));
          } catch (IOException e) {
            throw new StorageException(
                "Could not update the Excel workbook. Close it in Excel and try again. Your XML"
                    + " data is safe.",
                e);
          }
        });
  }

  private WorkbookInfo export(String id, String name, Year year) {
    String warning = null;
    try {
      excel.write(
          directory(id).resolve("excel"), name, year, new BudgetCategories().read(directory(id)));
    } catch (IOException | RuntimeException e) {
      LoggerFactory.getLogger(ProfileService.class)
          .warn("Excel export failed for profile {} year {}", id, year.year(), e);
      warning =
          "Your data is saved in XML, but Excel could not update. Close the workbook and click"
              + " Refresh or Download Excel to retry.";
    }
    return new WorkbookInfo(excel.fileName(name, year.year()), warning);
  }

  public <T> T withProfile(String id, BiFunction<Path, String, T> action) {
    return locked(
        () -> {
          String name = requireProfile(catalog(), id);
          return action.apply(directory(id), name);
        });
  }

  private Template template(String id) {
    return new MortgageStore().exists(directory(id)) ? Template.MORTGAGE : Template.BUDGET;
  }

  private String exportMortgage(String id, String name, String previousName) {
    try {
      var writer = new MortgageWorkbook();
      Path folder = directory(id).resolve("excel");
      Path current = writer.write(folder, name, new MortgageStore().read(directory(id)));
      if (previousName != null && !writer.fileName(previousName).equals(writer.fileName(name))) {
        Path old = folder.resolve(writer.fileName(previousName));
        if (Files.exists(old) && !Files.isSameFile(old, current)) Files.delete(old);
      }
      return null;
    } catch (Exception e) {
      LoggerFactory.getLogger(ProfileService.class)
          .warn("Mortgage export failed for profile {}", id, e);
      return "Profile saved. The mortgage workbook could not update; close Excel and retry its"
          + " download.";
    }
  }

  private Path directory(String id) {
    return id.equals("default") ? root : root.resolve("profiles").resolve(id);
  }

  private XmlLedgerStore store(String id) {
    return stores.computeIfAbsent(id, key -> new XmlLedgerStore(directory(key).toString()));
  }

  private static String requireProfile(Properties catalog, String id) {
    String name = catalog.getProperty(id);
    if (name == null) throw new NotFoundException("Profile not found. Refresh your profile list.");
    return name;
  }

  private static String validateName(String input, Properties catalog, String exceptId) {
    String name = Normalizer.normalize(input == null ? "" : input, Normalizer.Form.NFKC).strip();
    if (name.isEmpty()
        || name.length() > 60
        || name.codePoints()
            .anyMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT))
      throw new IllegalArgumentException(
          "Choose a profile name between 1 and 60 characters, without control characters.");
    if (catalog.stringPropertyNames().stream()
        .anyMatch(id -> !id.equals(exceptId) && catalog.getProperty(id).equalsIgnoreCase(name)))
      throw new ConflictException("A profile with that name already exists. Choose another name.");
    return name;
  }

  private Properties catalog() {
    Path file = root.resolve("profiles.properties");
    var properties = new Properties();
    if (!Files.exists(file)) {
      properties.setProperty("default", "Personal");
      saveCatalog(properties);
      return properties;
    }
    try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      properties.load(reader);
      if (!properties.containsKey("default")) throw new IOException("Missing default profile");
      for (String id : properties.stringPropertyNames()) {
        if (!id.equals("default") && !UUID.fromString(id).toString().equals(id))
          throw new IOException("Invalid profile ID");
        validateName(properties.getProperty(id), properties, id);
      }
      return properties;
    } catch (IOException | IllegalArgumentException | ConflictException e) {
      throw new StorageException(
          "The profile list could not be read. Its original file is preserved.", e);
    }
  }

  private void saveCatalog(Properties catalog) {
    Path temp = null;
    try {
      temp = Files.createTempFile(root, ".profiles-", ".tmp");
      try (var writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
        catalog.store(writer, "Hisabo profile names; IDs keep each ledger separate");
      }
      try (var channel = FileChannel.open(temp, WRITE)) {
        channel.force(true);
      }
      Path target = root.resolve("profiles.properties");
      if (Files.exists(target))
        Files.copy(
            target, root.resolve("profiles.properties.bak"), StandardCopyOption.REPLACE_EXISTING);
      Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw new StorageException(
          "Could not save the profile list. Your transaction data is unchanged.", e);
    } finally {
      if (temp != null)
        try {
          Files.deleteIfExists(temp);
        } catch (IOException ignored) {
        }
    }
  }

  private <T> T locked(IoSupplier<T> operation) {
    mutex.lock();
    try (var channel = FileChannel.open(root.resolve(".profiles.lock"), CREATE, WRITE);
        var lock = channel.lock()) {
      return operation.get();
    } catch (IOException e) {
      throw new StorageException("Could not access the profile workspace. Please try again.", e);
    } finally {
      mutex.unlock();
    }
  }

  private interface IoSupplier<T> {
    T get() throws IOException;
  }
}
