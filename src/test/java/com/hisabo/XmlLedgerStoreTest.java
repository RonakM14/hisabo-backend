package com.hisabo;

import static org.junit.jupiter.api.Assertions.*;

import com.hisabo.domain.Ledger.*;
import com.hisabo.service.LedgerService;
import com.hisabo.storage.XmlLedgerStore;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class XmlLedgerStoreTest {
  @TempDir Path directory;

  TransactionInput input(long revision, String description, String amount, String date) {
    return new TransactionInput(
        revision,
        Type.EXPENSE,
        description,
        new BigDecimal(amount),
        Category.FOOD.name(),
        Account.CHECKING,
        LocalDate.parse(date));
  }

  @Test
  void survivesRestartWithExactDecimalsAndEscapedXml() throws Exception {
    var store = new XmlLedgerStore(directory.toString());
    var service = new LedgerService(store);
    service.add(2026, input(0, "Coffee & <pastry>", "10.10", "2026-09-14"));
    Year reloaded = new XmlLedgerStore(directory.toString()).read(2026);
    assertEquals(12, reloaded.months().size());
    assertEquals(1, reloaded.revision());
    var saved = reloaded.months().get(8).transactions().getFirst();
    assertEquals(new BigDecimal("10.10"), saved.amount());
    assertEquals("Coffee & <pastry>", saved.description());
    assertTrue(
        Files.readString(directory.resolve("ledger-2026.xml"))
            .contains("Coffee &amp; &lt;pastry&gt;"));
    assertTrue(store.read(2025).months().stream().allMatch(m -> m.transactions().isEmpty()));
    assertFalse(Files.exists(directory.resolve("ledger-2025.xml")), "Reads must not create files");
  }

  @Test
  void editMovesTransactionAcrossMonthsAndBackupKeepsPreviousVersion() throws Exception {
    var store = new XmlLedgerStore(directory.toString());
    var service = new LedgerService(store);
    var added = service.add(2026, input(0, "Lunch", "12.50", "2026-08-01"));
    var id = added.months().get(7).transactions().getFirst().id();
    var moved = service.edit(2026, id, input(1, "Lunch updated", "14.50", "2026-09-01"));
    assertTrue(moved.months().get(7).transactions().isEmpty());
    assertEquals(id, moved.months().get(8).transactions().getFirst().id());
    assertTrue(
        Files.readString(directory.resolve("ledger-2026.xml.bak")).contains("Lunch</description>"));
    assertTrue(service.delete(2026, id, 2).months().get(8).transactions().isEmpty());
    assertThrows(LedgerService.NotFoundException.class, () -> service.delete(2026, id, 3));
    assertEquals(3, store.read(2026).revision());
  }

  @Test
  void concurrentWritersCannotLoseAnUpdate() throws Exception {
    var store = new XmlLedgerStore(directory.toString());
    var service = new LedgerService(store);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var tasks =
          List.<Callable<Boolean>>of(() -> attempt(service, "One"), () -> attempt(service, "Two"));
      int saved = 0;
      for (var future : executor.invokeAll(tasks)) if (future.get()) saved++;
      assertEquals(1, saved);
      assertEquals(1, store.read(2026).months().get(8).transactions().size());
    }
  }

  boolean attempt(LedgerService service, String label) {
    try {
      service.add(2026, input(0, label, "1.00", "2026-09-01"));
      return true;
    } catch (XmlLedgerStore.ConflictException expected) {
      return false;
    }
  }

  @Test
  void corruptFileIsNeverSilentlyReplaced() throws Exception {
    Path file = directory.resolve("ledger-2026.xml");
    Files.writeString(file, "not XML");
    var service = new LedgerService(new XmlLedgerStore(directory.toString()));
    assertThrows(
        XmlLedgerStore.StorageException.class,
        () -> service.add(2026, input(0, "Test", "1.00", "2026-09-01")));
    assertEquals("not XML", Files.readString(file));
  }

  @Test
  void externalEntitiesAreRejected() throws Exception {
    String xml =
        "<!DOCTYPE ledger [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]><ledger>&xxe;</ledger>";
    Files.writeString(directory.resolve("ledger-2026.xml"), xml);
    assertThrows(
        XmlLedgerStore.StorageException.class,
        () -> new XmlLedgerStore(directory.toString()).read(2026));
  }

  @Test
  void yearAndCategoryMismatchLeaveFileUntouched() {
    var store = new XmlLedgerStore(directory.toString());
    var service = new LedgerService(store);
    assertThrows(
        IllegalArgumentException.class,
        () -> service.add(2026, input(0, "Wrong year", "1.00", "2025-01-01")));
    assertThrows(IllegalArgumentException.class, () -> store.read(1999));
    assertEquals(0, store.read(2026).revision());
  }
}
