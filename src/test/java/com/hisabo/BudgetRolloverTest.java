package com.hisabo;

import static org.junit.jupiter.api.Assertions.*;

import com.hisabo.domain.Ledger.*;
import com.hisabo.profile.*;
import com.hisabo.service.LedgerService;
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

class BudgetRolloverTest {
  @TempDir Path directory;

  XmlLedgerStore store() {
    return new XmlLedgerStore(directory.toString());
  }

  BudgetInput budget(long revision, String food) {
    var amounts = new LinkedHashMap<String, BigDecimal>();
    BudgetCategories.defaultIds()
        .forEach(id -> amounts.put(id, new BigDecimal(id.equals("FOOD") ? food : "0.00")));
    return new BudgetInput(revision, amounts);
  }

  @Test
  void copiesForwardPersistsAndKeepsAnExplicitFuturePlanIncludingZero() throws Exception {
    var store = store();
    var service = new LedgerService(store);
    service.budgets(2026, 12, budget(0, "0.00"));
    var year = service.budgets(2026, 10, budget(1, "200.25"));
    assertEquals(BudgetOrigin.UNSET, year.months().get(8).budgetOrigin());
    assertEquals(BudgetOrigin.CARRIED, year.months().get(10).budgetOrigin());
    assertEquals("2026-10", year.months().get(10).budgetSource());
    assertEquals(new BigDecimal("200.25"), year.months().get(10).budgets().get("FOOD"));
    assertEquals(BudgetOrigin.MANUAL, year.months().get(11).budgetOrigin());
    assertEquals(new BigDecimal("0.00"), year.months().get(11).budgets().get("FOOD"));
    assertTrue(
        Files.readString(directory.resolve("ledger-2026.xml"))
            .contains("budgetOrigin=\"CARRIED\""));
    var restarted = store();
    assertEquals(year, restarted.read(2026));
    var revised = new LedgerService(restarted).budgets(2026, 10, budget(2, "300.00"));
    assertEquals(new BigDecimal("300.00"), revised.months().get(10).budgets().get("FOOD"));
    assertEquals(
        new BigDecimal("0.00"), restarted.read(2027).months().getFirst().budgets().get("FOOD"));
  }

  @Test
  void crossesTheYearBoundaryAndRejectsStaleWritesAfterSourceChanges() throws Exception {
    var store = store();
    var service = new LedgerService(store);
    service.budgets(2026, 12, budget(0, "125.50"));
    var next = store.read(2027);
    assertEquals("2026-12", next.months().getFirst().budgetSource());
    assertEquals(new BigDecimal("125.50"), next.months().getFirst().budgets().get("FOOD"));
    assertEquals(1, next.revision());
    assertEquals(next.revision(), store.read(2027).revision(), "Repeated reads must be idempotent");
    assertTrue(Files.exists(directory.resolve("ledger-2027.xml")));
    service.budgets(2026, 12, budget(1, "150.75"));
    assertThrows(ConflictException.class, () -> service.budgets(2027, 1, budget(1, "500")));
    var refreshed = store.read(2027);
    assertEquals(2, refreshed.revision());
    assertEquals(new BigDecimal("150.75"), refreshed.months().getFirst().budgets().get("FOOD"));
    service.budgets(2027, 1, budget(refreshed.revision(), "250.00"));
    assertEquals(new BigDecimal("250.00"), store.read(2027).months().get(1).budgets().get("FOOD"));
  }

  @Test
  void existingLegacyAllocationsArePreservedAndUnsetMonthsInherit() throws Exception {
    var service = new LedgerService(store());
    service.budgets(2026, 10, budget(0, "100"));
    Path file = directory.resolve("ledger-2026.xml");
    String xml =
        Files.readString(file)
            .replace("schemaVersion=\"3\"", "schemaVersion=\"2\"")
            .replaceAll(" budgetOrigin=\"[^\"]*\"", "")
            .replaceAll(" budgetSource=\"[^\"]*\"", "");
    Files.writeString(file, xml);
    var legacy = store().read(2026);
    assertEquals(BudgetOrigin.MANUAL, legacy.months().get(10).budgetOrigin());
    service.budgets(2026, 10, budget(legacy.revision(), "150"));
    assertEquals(
        new BigDecimal("100.00"), store().read(2026).months().get(10).budgets().get("FOOD"));
  }

  @Test
  void customCategoriesCarryOnlyInsideTheirOwnProfile() {
    var profiles = new ProfileService(directory.toString(), store(), new ExcelLedgerWriter());
    var first = profiles.create("Household");
    var second = profiles.create("Separate budget");
    var entries = new ArrayList<>(BudgetCategories.defaults().entries());
    String id = "CUSTOM_" + UUID.randomUUID();
    entries.add(new BudgetCategories.Entry(id, "School", "#123abc", true));
    profiles.categories(first.id(), 2026, new BudgetCategories.Input(0L, entries));
    var amounts = new LinkedHashMap<>(budget(0, "10").budgets());
    amounts.put(id, new BigDecimal("50.00"));
    profiles.budgets(first.id(), 2026, 12, new BudgetInput(0L, amounts));
    assertEquals(
        new BigDecimal("50.00"),
        profiles.year(first.id(), 2027).months().getFirst().budgets().get(id));
    assertEquals(
        0,
        profiles.year(second.id(), 2027).months().getFirst().budgetSummary().allocated().signum());
  }

  @Test
  void savingsUseUnallocatedIncomeAndMatchExcelWithoutChangingTransactions() throws Exception {
    var profiles = new ProfileService(directory.toString(), store(), new ExcelLedgerWriter());
    profiles.profiles();
    var tx =
        new TransactionInput(
            0L,
            Type.INCOME,
            "Salary",
            new BigDecimal("1000.10"),
            "SALARY",
            Account.CHECKING,
            LocalDate.of(2026, 10, 4));
    profiles.add("default", 2026, tx);
    var result = profiles.budgets("default", 2026, 10, budget(1, "600.05"));
    var summary = result.months().get(9).budgetSummary();
    assertEquals(new BigDecimal("400.05"), summary.savings());
    assertEquals(new BigDecimal("0.00"), summary.shortfall());
    assertEquals(1, result.months().get(9).transactions().size());
    try (var workbook =
        new XSSFWorkbook(new ByteArrayInputStream(profiles.download("default", 2026).bytes()))) {
      assertEquals(
          400.05, workbook.getSheet("October").getRow(11).getCell(4).getNumericCellValue(), 0.001);
      assertEquals(
          600.05, workbook.getSheet("November").getRow(11).getCell(5).getNumericCellValue(), 0.001);
      assertEquals(
          400.05, workbook.getSheet("Overview").getRow(18).getCell(5).getNumericCellValue(), 0.001);
    }
    var over =
        profiles.budgets("default", 2026, 10, budget(2, "1200.20")).months().get(9).budgetSummary();
    assertEquals(new BigDecimal("0.00"), over.savings());
    assertEquals(new BigDecimal("200.10"), over.shortfall());
  }

  @Test
  void missingSourceDoesNotReplaceAnAlreadyCopiedLedger() throws Exception {
    new LedgerService(store()).budgets(2026, 12, budget(0, "100"));
    store().read(2027);
    Path target = directory.resolve("ledger-2027.xml");
    byte[] before = Files.readAllBytes(target);
    Files.delete(directory.resolve("ledger-2026.xml"));
    assertThrows(StorageException.class, () -> store().read(2027));
    assertArrayEquals(before, Files.readAllBytes(target));
  }
}
