package com.hisabo;

import static org.junit.jupiter.api.Assertions.*;

import com.hisabo.domain.Ledger.*;
import com.hisabo.profile.*;
import com.hisabo.profile.BudgetCategories.*;
import com.hisabo.storage.XmlLedgerStore;
import com.hisabo.storage.XmlLedgerStore.*;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BudgetCategoriesTest {
  @TempDir Path root;

  ProfileService service() {
    return new ProfileService(
        root.toString(), new XmlLedgerStore(root.toString()), new ExcelLedgerWriter());
  }

  Entry custom(String label) {
    return new Entry("CUSTOM_" + UUID.randomUUID(), label, "#123abc", true);
  }

  List<Entry> with(Entry entry) {
    var result = new ArrayList<>(BudgetCategories.defaults().entries());
    result.add(entry);
    return result;
  }

  TransactionInput tx(long revision, String category) {
    return new TransactionInput(
        revision,
        Type.EXPENSE,
        "A saved expense",
        new BigDecimal("27.50"),
        category,
        Account.CHECKING,
        LocalDate.of(2026, 10, 3));
  }

  @Test
  void categoriesPersistAcrossYearsAndProfilesWhileRemovalPreservesHistory() throws Exception {
    var service = service();
    var profile = service.create("Custom budget");
    var other = service.create("Separate");
    var travel = custom("Travel & holidays");
    var entries = with(travel);
    var configured = service.categories(profile.id(), 2026, new Input(0L, entries));
    assertEquals(0, configured.revision());
    assertEquals(1, configured.categories().revision());
    var budgets = new LinkedHashMap<String, BigDecimal>();
    entries.forEach(
        c ->
            budgets.put(
                c.id(), c.id().equals(travel.id()) ? new BigDecimal("200.00") : BigDecimal.ZERO));
    service.budgets(profile.id(), 2026, 10, new BudgetInput(0L, budgets));
    var saved = service.add(profile.id(), 2026, tx(1, travel.id()));
    var transaction = saved.months().get(9).transactions().getFirst();
    byte[] before =
        Files.readAllBytes(
            root.resolve("profiles").resolve(profile.id()).resolve("ledger-2026.xml"));
    var removed =
        entries.stream()
            .map(
                c ->
                    c.id().equals(travel.id()) ? new Entry(c.id(), c.label(), c.color(), false) : c)
            .toList();
    service.categories(profile.id(), 2026, new Input(1L, removed));
    assertArrayEquals(
        before,
        Files.readAllBytes(
            root.resolve("profiles").resolve(profile.id()).resolve("ledger-2026.xml")));
    var restarted = service();
    assertEquals(removed, restarted.year(profile.id(), 2030).categories().entries());
    assertEquals(6, restarted.year(other.id(), 2026).categories().entries().size());
    assertThrows(
        IllegalArgumentException.class, () -> restarted.add(other.id(), 2026, tx(0, travel.id())));
    assertThrows(
        ConflictException.class, () -> restarted.add(profile.id(), 2026, tx(2, travel.id())));
    var edited = restarted.edit(profile.id(), 2026, transaction.id(), tx(2, travel.id()));
    assertEquals(1, edited.months().get(9).transactions().size());
    assertEquals(new BigDecimal("200.00"), edited.months().get(9).budgets().get(travel.id()));
    restarted.categories(profile.id(), 2026, new Input(2L, entries));
    assertEquals(
        2,
        restarted
            .add(profile.id(), 2026, tx(3, travel.id()))
            .months()
            .get(9)
            .transactions()
            .size());
  }

  @Test
  void invalidAndStaleConfigurationNeverOverwritesSavedSettings() throws Exception {
    var service = service();
    service.profiles();
    var travel = custom("Travel");
    var entries = with(travel);
    service.categories("default", 2026, new Input(0L, entries));
    byte[] before = Files.readAllBytes(root.resolve("budget-categories.xml"));
    assertThrows(
        ConflictException.class, () -> service.categories("default", 2026, new Input(0L, entries)));
    var duplicate = new ArrayList<>(entries);
    duplicate.add(custom(" TRAVEL "));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.categories("default", 2026, new Input(1L, duplicate)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.categories(
                "default", 2026, new Input(1L, BudgetCategories.defaults().entries())));
    var invalid = new ArrayList<>(entries);
    invalid.add(new Entry("../../file", "Unsafe", "#123abc", true));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.categories("default", 2026, new Input(1L, invalid)));
    assertArrayEquals(before, Files.readAllBytes(root.resolve("budget-categories.xml")));
  }

  @Test
  void workbookExpandsForCustomCategoriesAndUsesStableIdsForFormulas() throws Exception {
    var service = service();
    var profile = service.create("Workbook");
    var wildcard = custom("*");
    var entries = with(wildcard);
    for (int i = 0; i < 9; i++) entries.add(custom("Category " + i));
    service.categories(profile.id(), 2026, new Input(0L, entries));
    service.add(profile.id(), 2026, tx(0, wildcard.id()));
    service.add(profile.id(), 2026, tx(1, "FOOD"));
    try (var book =
        new XSSFWorkbook(new ByteArrayInputStream(service.download(profile.id(), 2026).bytes()))) {
      var october = book.getSheet("October");
      assertEquals("*", october.getRow(9).getCell(0).getStringCellValue());
      assertEquals(27.50, october.getRow(9).getCell(2).getNumericCellValue());
      assertEquals(55, book.getSheet("Overview").getRow(18).getCell(2).getNumericCellValue());
      assertTrue(october.isColumnHidden(8));
      assertEquals("Date", october.getRow(23).getCell(0).getStringCellValue());
      assertEquals("A saved expense", october.getRow(24).getCell(1).getStringCellValue());
    }
  }

  @Test
  void missingCategorySettingsCannotMutateAnExistingCustomLedger() throws Exception {
    var service = service();
    service.profiles();
    var category = custom("School");
    service.categories("default", 2026, new Input(0L, with(category)));
    service.add("default", 2026, tx(0, category.id()));
    Path ledger = root.resolve("ledger-2026.xml");
    byte[] before = Files.readAllBytes(ledger);
    Files.delete(root.resolve("budget-categories.xml"));
    assertThrows(StorageException.class, () -> service.add("default", 2026, tx(1, "FOOD")));
    assertThrows(StorageException.class, () -> service.download("default", 2026));
    assertArrayEquals(before, Files.readAllBytes(ledger));
  }

  @Test
  void oldXmlRemainsReadableAndCorruptCategoryFileIsPreserved() throws Exception {
    var service = service();
    service.add("default", 2026, tx(0, "FOOD"));
    var ledger = root.resolve("ledger-2026.xml");
    Files.writeString(
        ledger, Files.readString(ledger).replace("schemaVersion=\"3\"", "schemaVersion=\"1\""));
    assertEquals(1, service.year("default", 2026).months().get(9).transactions().size());
    var file = root.resolve("budget-categories.xml");
    String corrupt =
        "<!DOCTYPE budgetCategories [<!ENTITY xxe SYSTEM"
            + " 'file:///etc/passwd'>]><budgetCategories>&xxe;</budgetCategories>";
    Files.writeString(file, corrupt);
    assertThrows(StorageException.class, () -> service.year("default", 2026));
    assertEquals(corrupt, Files.readString(file));
  }

  @Test
  void allCategoriesCanBeRemovedAndExcelFailureStillReportsCommittedSettings() throws Exception {
    var service = service();
    service.profiles();
    Files.writeString(root.resolve("excel"), "blocked export folder");
    var entries =
        BudgetCategories.defaults().entries().stream()
            .map(c -> new Entry(c.id(), c.label(), c.color(), false))
            .toList();
    var saved = service.categories("default", 2026, new Input(0L, entries));
    assertNotNull(saved.workbook().warning());
    assertEquals(entries, new BudgetCategories().read(root).entries());
    service.budgets("default", 2026, 10, new BudgetInput(0L, Map.of()));
    Files.delete(root.resolve("excel"));
    assertNull(service.year("default", 2026).workbook().warning());
    try (var book =
        new XSSFWorkbook(new ByteArrayInputStream(service.download("default", 2026).bytes()))) {
      assertEquals(0, book.getSheet("Overview").getRow(18).getCell(4).getNumericCellValue());
    }
  }
}
