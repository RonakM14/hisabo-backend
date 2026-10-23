package com.hisabo;

import static org.junit.jupiter.api.Assertions.*;

import com.hisabo.domain.Ledger.*;
import com.hisabo.profile.*;
import com.hisabo.service.LedgerService.NotFoundException;
import com.hisabo.storage.XmlLedgerStore;
import com.hisabo.storage.XmlLedgerStore.ConflictException;
import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfileServiceTest {
  @TempDir Path root;

  ProfileService service() {
    return new ProfileService(
        root.toString(), new XmlLedgerStore(root.toString()), new ExcelLedgerWriter());
  }

  TransactionInput transaction(long revision, String description) {
    return new TransactionInput(
        revision,
        Type.EXPENSE,
        description,
        new BigDecimal("12.34"),
        Category.FOOD.name(),
        Account.CHECKING,
        LocalDate.of(2026, 9, 20));
  }

  @Test
  void existingDataIsPersonalAndProfilesStaySeparateAfterRestart() throws Exception {
    var legacy = new XmlLedgerStore(root.toString());
    new com.hisabo.service.LedgerService(legacy).add(2026, transaction(0, "Existing record"));
    byte[] original = Files.readAllBytes(root.resolve("ledger-2026.xml"));
    var service = service();
    assertEquals("Personal", service.profiles().getFirst().name());
    var profile = service.create("Ronak");
    assertTrue(
        service.year(profile.id(), 2026).months().stream()
            .allMatch(m -> m.transactions().isEmpty()));
    var saved = service.add(profile.id(), 2026, transaction(0, "Ronak's lunch"));
    assertEquals(1, saved.revision());
    assertNull(saved.workbook().warning());
    var other = service.create("Family");
    assertEquals(0, service.year(other.id(), 2026).revision());
    var restarted = service();
    assertEquals(3, restarted.profiles().size());
    assertEquals(
        "Ronak's lunch",
        restarted.year(profile.id(), 2026).months().get(8).transactions().getFirst().description());
    assertEquals(
        "Existing record",
        restarted.year("default", 2026).months().get(8).transactions().getFirst().description());
    assertArrayEquals(original, Files.readAllBytes(root.resolve("ledger-2026.xml")));
    assertThrows(
        ConflictException.class, () -> restarted.add(profile.id(), 2026, transaction(0, "Stale")));
    assertThrows(
        NotFoundException.class,
        () ->
            restarted.delete(
                other.id(), 2026, saved.months().get(8).transactions().getFirst().id(), 0));
    var budgets = new LinkedHashMap<String, BigDecimal>();
    for (Category c : Category.values())
      if (c.type == Type.EXPENSE) budgets.put(c.name(), BigDecimal.valueOf(100));
    restarted.budgets(profile.id(), 2026, 9, new BudgetInput(1L, budgets));
    assertEquals(
        BigDecimal.ZERO.setScale(2),
        restarted.year(other.id(), 2026).months().get(8).budgets().get(Category.FOOD.name()));
  }

  @Test
  void workbookHasMonthlyTabsTypedValuesAndCalculatedTotalsAndRenamesSafely() throws Exception {
    var service = service();
    var profile = service.create("Ronak / ઘર");
    var saved =
        service.add(profile.id(), 2026, transaction(0, "=HYPERLINK(\"https://invalid.example\")"));
    Path exports = root.resolve("profiles").resolve(profile.id()).resolve("excel");
    assertTrue(Files.isRegularFile(exports.resolve("Ronak - ઘર-2026.xlsx")));
    try (var book =
        new XSSFWorkbook(new ByteArrayInputStream(service.download(profile.id(), 2026).bytes()))) {
      assertEquals(13, book.getNumberOfSheets());
      assertEquals("January", book.getSheetName(1));
      assertEquals("December", book.getSheetName(12));
      var september = book.getSheet("September");
      assertEquals(CellType.STRING, september.getRow(14).getCell(1).getCellType());
      assertTrue(september.getRow(14).getCell(1).getStringCellValue().startsWith("=HYPERLINK"));
      assertEquals(CellType.NUMERIC, september.getRow(14).getCell(0).getCellType());
      assertEquals(12.34, september.getRow(11).getCell(1).getNumericCellValue(), 0.001);
      assertEquals(
          -12.34, book.getSheet("Overview").getRow(18).getCell(3).getNumericCellValue(), 0.001);
      assertEquals(12.34, september.getRow(4).getCell(2).getNumericCellValue(), 0.001);
      assertEquals(0, book.getSheet("January").getRow(11).getCell(2).getNumericCellValue());
    }
    service.year(profile.id(), 2025); // Even an empty year viewed/exported must be renamed.
    service.rename(profile.id(), "Family");
    assertFalse(Files.exists(exports.resolve("Ronak - ઘર-2026.xlsx")));
    assertTrue(Files.exists(exports.resolve("Family-2025.xlsx")));
    assertEquals(saved.revision(), service().year(profile.id(), 2026).revision());
    var tx = saved.months().get(8).transactions().getFirst();
    service.edit(
        profile.id(),
        2026,
        tx.id(),
        new TransactionInput(
            1L,
            Type.EXPENSE,
            "Moved",
            new BigDecimal("7.50"),
            Category.FOOD.name(),
            Account.CASH,
            LocalDate.of(2026, 10, 1)));
    try (var book =
        new XSSFWorkbook(new ByteArrayInputStream(service.download(profile.id(), 2026).bytes()))) {
      assertEquals(0, book.getSheet("September").getRow(11).getCell(1).getNumericCellValue());
      assertEquals(7.50, book.getSheet("October").getRow(11).getCell(1).getNumericCellValue());
    }
    service.delete(profile.id(), 2026, tx.id(), 2);
    try (var book =
        new XSSFWorkbook(new ByteArrayInputStream(service.download(profile.id(), 2026).bytes()))) {
      assertEquals(0, book.getSheet("Overview").getRow(18).getCell(2).getNumericCellValue());
    }
  }

  @Test
  void invalidDuplicateAndUnknownProfilesCannotReachOtherFiles() throws Exception {
    var service = service();
    for (String name : List.of(" ", "a\nb", "a".repeat(61), "\u200B"))
      assertThrows(IllegalArgumentException.class, () -> service.create(name));
    service.create("Family");
    assertThrows(ConflictException.class, () -> service.create(" family "));
    assertThrows(ConflictException.class, () -> service.rename("default", "FAMILY"));
    assertThrows(NotFoundException.class, () -> service.year("../..", 2026));
    var profile = service.create("../../A:B");
    assertFalse(service.year(profile.id(), 2026).workbook().fileName().contains("/"));
    assertThrows(IllegalArgumentException.class, () -> service.year(profile.id(), 1999));
  }

  @Test
  void workbookFailureDoesNotHideCommittedTransaction() throws Exception {
    var service = service();
    service.profiles();
    Files.writeString(root.resolve("excel"), "A file prevents creating the export directory");
    var saved = service.add("default", 2026, transaction(0, "Saved even if Excel is unavailable"));
    assertNotNull(saved.workbook().warning());
    assertEquals(1, new XmlLedgerStore(root.toString()).read(2026).revision());
    Files.delete(root.resolve("excel"));
    assertNull(service.year("default", 2026).workbook().warning());
    assertTrue(Files.exists(root.resolve("excel/Personal-2026.xlsx")));
  }

  @Test
  void concurrentCreationDoesNotLoseProfilesOrAllowDuplicateNames() throws Exception {
    var first = service();
    var second = service();
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var start = new CountDownLatch(1);
      var tasks =
          pool.invokeAll(
              List.<Callable<Boolean>>of(
                  () -> {
                    start.countDown();
                    start.await();
                    try {
                      first.create("Shared");
                      return true;
                    } catch (ConflictException e) {
                      return false;
                    }
                  },
                  () -> {
                    start.await();
                    try {
                      second.create("Shared");
                      return true;
                    } catch (ConflictException e) {
                      return false;
                    }
                  }));
      assertNotEquals(tasks.get(0).get(), tasks.get(1).get());
      assertEquals(2, first.profiles().size());
    }
  }
}
