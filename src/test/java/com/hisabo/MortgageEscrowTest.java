package com.hisabo;

import static com.hisabo.MortgageCalculatorTest.n;
import static org.junit.jupiter.api.Assertions.*;

import com.hisabo.mortgage.*;
import com.hisabo.mortgage.Mortgage.*;
import com.hisabo.profile.*;
import com.hisabo.profile.ProfileService.Template;
import com.hisabo.storage.XmlLedgerStore;
import com.hisabo.storage.XmlLedgerStore.*;
import java.io.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MortgageEscrowTest {
  @TempDir Path root;

  static Loan loan() {
    return new Loan(
        LocalDate.of(2020, 1, 31),
        LocalDate.of(2026, 10, 31),
        n("1200"),
        n("0"),
        InterestConvention.MONTHLY,
        PaymentMode.PAYMENT,
        n("100"),
        null,
        n("50"));
  }

  static EscrowChange change(String month, String amount, EscrowScope scope) {
    return new EscrowChange(month, n(amount), scope);
  }

  static Scenario scenario(List<EscrowChange> entries) {
    return new Scenario(n("0"), null, n("0"), null, List.of(), entries);
  }

  static List<EscrowChange> entries() {
    return List.of(
        change("2026-11", "60", EscrowScope.FROM_MONTH),
        change("2026-12", "80", EscrowScope.THIS_MONTH),
        change("2027-01", "0", EscrowScope.FROM_MONTH),
        change("2027-03", "70", EscrowScope.THIS_MONTH),
        change("2027-04", "100", EscrowScope.FROM_MONTH));
  }

  @Test
  void carryForwardOneMonthOverridesAndZeroAmountsMatchAnnualAndLifetimeTotals() {
    var plain = MortgageCalculator.analyze(loan(), Scenario.empty(), List.of());
    var result = MortgageCalculator.analyze(loan(), scenario(entries()), List.of());
    assertEquals(plain.baseline(), result.baseline());
    assertEquals(plain.accelerated(), result.accelerated());
    assertEquals(plain.interestSaved(), result.interestSaved());
    assertEquals(plain.monthsSaved(), result.monthsSaved());
    assertEquals(n("160.00"), result.monthlyHousingPayment());
    assertEquals(n("910.00"), result.housing().escrowTotal());
    assertEquals(n("2110.00"), result.housing().totalPayments());
    assertEquals(12, result.housing().months().size());
    assertEquals(n("1100.00"), result.housing().months().getFirst().balance());
    assertEquals(n("0.00"), result.housing().months().getLast().balance());
    assertEquals(n("80.00"), result.housing().months().get(1).escrow());
    assertEquals(n("0.00"), result.housing().months().get(3).escrow());
    assertEquals("2027-01", result.housing().months().get(3).escrowSourceMonth());
    assertEquals(n("70.00"), result.housing().months().get(4).escrow());
    assertEquals(n("100.00"), result.housing().months().get(5).escrow());
    assertEquals(
        new HousingYear(2026, 2, n("200.00"), n("0.00"), n("140.00"), n("340.00")),
        result.housing().years().getFirst());
    assertEquals(n("1770.00"), result.housing().years().getLast().totalPayment());
  }

  @Test
  void balanceIncludesInterestAndExtraPrincipalButNeverEscrow() {
    var loan =
        new Loan(
            loan().startDate(),
            loan().balanceDate(),
            n("1200"),
            n("12"),
            InterestConvention.MONTHLY,
            PaymentMode.PAYMENT,
            n("110"),
            null,
            n("50"));
    var plan =
        new Scenario(
            n("10"),
            "2026-11",
            n("0"),
            null,
            List.of(new OneTimePayment(UUID.randomUUID(), LocalDate.of(2026, 11, 1), n("100"))),
            entries());
    var result = MortgageCalculator.analyze(loan, plan, List.of());
    // 1,200 - (110 - 12 interest) - 10 monthly extra - 100 one-time principal.
    assertEquals(n("992.00"), result.housing().months().getFirst().balance());
    assertEquals(n("280.00"), result.housing().months().getFirst().totalPayment());
    for (int i = 0; i < result.housing().months().size(); i++)
      assertEquals(
          result.accelerated().payments().get(i).balance(),
          result.housing().months().get(i).balance());
  }

  @Test
  void oneMonthChangeRevertsAndOverridesAreOrderIndependent() {
    var entries =
        List.of(
            change("2027-01", "80", EscrowScope.THIS_MONTH),
            change("2026-12", "65", EscrowScope.FROM_MONTH));
    var projection = MortgageCalculator.analyze(loan(), scenario(entries), List.of()).housing();
    assertEquals(n("50.00"), projection.months().get(0).escrow());
    assertNull(projection.months().get(0).escrowSourceMonth());
    assertEquals(n("65.00"), projection.months().get(1).escrow());
    assertEquals(n("80.00"), projection.months().get(2).escrow());
    assertEquals(n("65.00"), projection.months().get(3).escrow());
    var reverse = new ArrayList<>(entries);
    Collections.reverse(reverse);
    assertEquals(
        projection, MortgageCalculator.analyze(loan(), scenario(reverse), List.of()).housing());
  }

  @Test
  void payoffCapsLoanPaymentAndStopsHousingProjectionWithoutCallingEscrowInterestSavings() {
    var before = MortgageCalculator.analyze(loan(), Scenario.empty(), List.of());
    var accelerated =
        new Scenario(
            n("0"),
            null,
            n("0"),
            null,
            List.of(new OneTimePayment(UUID.randomUUID(), LocalDate.of(2026, 11, 1), n("5000"))),
            entries());
    var result = MortgageCalculator.analyze(loan(), accelerated, List.of());
    assertEquals(1, result.housing().months().size());
    assertEquals(n("1260.00"), result.housing().totalPayments());
    assertEquals(n("60.00"), result.housing().escrowTotal());
    assertEquals(n("1100.00"), result.housing().months().getFirst().extraPrincipal());
    assertEquals(n("0.00"), result.housing().months().getFirst().balance());
    assertEquals(n("0.00"), result.interestSaved());
    assertEquals(before.baseline(), result.baseline());
    var paid =
        new Loan(
            loan().startDate(),
            loan().balanceDate(),
            n("0"),
            n("0"),
            InterestConvention.MONTHLY,
            PaymentMode.PAYMENT,
            n("100"),
            null,
            n("50"));
    var empty = MortgageCalculator.analyze(paid, scenario(entries()), List.of());
    assertTrue(empty.housing().months().isEmpty());
    assertEquals(n("0.00"), empty.housing().totalPayments());
  }

  @Test
  void rejectsDuplicateMonthsMalformedDatesAmountsAndNullScopes() {
    for (var entry :
        List.of(
            change("2026-13", "5", EscrowScope.FROM_MONTH),
            change("2026-1", "5", EscrowScope.FROM_MONTH),
            change("2101-01", "5", EscrowScope.FROM_MONTH),
            change("2026-11", "-1", EscrowScope.FROM_MONTH),
            change("2026-11", "1000001", EscrowScope.FROM_MONTH),
            change("2026-11", "0.001", EscrowScope.FROM_MONTH),
            change("2026-11", "5", null))) {
      assertThrows(
          IllegalArgumentException.class,
          () -> MortgageCalculator.analyze(loan(), scenario(List.of(entry)), List.of()));
    }
    var entry = change("2026-11", "5", EscrowScope.FROM_MONTH);
    assertThrows(
        IllegalArgumentException.class,
        () -> MortgageCalculator.analyze(loan(), scenario(List.of(entry, entry)), List.of()));
  }

  @Test
  void xmlReloadExcelProfileIsolationOmittedFieldsAndResetsKeepFinancialDataConsistent()
      throws Exception {
    var profiles =
        new ProfileService(
            root.toString(), new XmlLedgerStore(root.toString()), new ExcelLedgerWriter());
    var home = profiles.create("Escrow Home", Template.MORTGAGE);
    var other = profiles.create("Other Escrow Home", Template.MORTGAGE);
    var service = new MortgageService(profiles);
    var saved = service.save(home.id(), new PlanInput(0L, loan(), scenario(entries())));
    assertNull(saved.workbookWarning());
    assertTrue(saved.supportsMonthlyEscrow());
    var reloaded = new MortgageService(profiles).read(home.id());
    assertEquals(saved.analysis().housing(), reloaded.analysis().housing());
    assertTrue(service.read(other.id()).scenario().escrowEntries().isEmpty());
    assertThrows(
        ConflictException.class,
        () -> service.save(home.id(), new PlanInput(0L, loan(), Scenario.empty())));
    // A request from an old client must not silently erase the newly saved escrow schedule.
    var oldClient = new Scenario(n("0"), null, n("0"), null, List.of(), null);
    assertEquals(
        saved.analysis().housing(),
        service.preview(home.id(), new PreviewInput(loan(), oldClient)).housing());
    var retained = service.save(home.id(), new PlanInput(1L, loan(), oldClient));
    assertEquals(entries(), retained.scenario().escrowEntries());
    try (var book =
        new XSSFWorkbook(new ByteArrayInputStream(service.download(home.id()).bytes()))) {
      assertEquals(8, book.getNumberOfSheets());
      assertEquals(
          60, book.getSheet("Monthly housing payments").getRow(4).getCell(3).getNumericCellValue());
      assertEquals(
          160,
          book.getSheet("Monthly housing payments").getRow(4).getCell(4).getNumericCellValue());
      assertEquals(
          "Remaining balance",
          book.getSheet("Monthly housing payments").getRow(3).getCell(5).getStringCellValue());
      assertEquals(
          1100,
          book.getSheet("Monthly housing payments").getRow(4).getCell(5).getNumericCellValue());
      assertEquals(
          340, book.getSheet("Yearly housing totals").getRow(4).getCell(5).getNumericCellValue());
      assertEquals(
          2110, book.getSheet("Yearly housing totals").getRow(7).getCell(5).getNumericCellValue());
      assertEquals(
          "This month only",
          book.getSheet("Escrow changes").getRow(5).getCell(2).getStringCellValue());
    }
    var reset = service.save(home.id(), new PlanInput(2L, loan(), scenario(List.of())));
    assertEquals(n("600.00"), reset.analysis().housing().escrowTotal());
    assertTrue(reset.scenario().escrowEntries().isEmpty());
    Path dir = root.resolve("profiles").resolve(home.id());
    assertTrue(Files.readString(dir.resolve("mortgage.xml.bak")).contains("escrowChange"));
  }

  @Test
  void versionTwoDefaultsArePreservedAndDamagedVersionThreeIsNotOverwritten() throws Exception {
    var store = new MortgageStore();
    store.write(root, new Plan(1L, loan(), Scenario.empty(), List.of()));
    Path file = root.resolve("mortgage.xml");
    String old =
        Files.readString(file)
            .replace("schemaVersion=\"4\"", "schemaVersion=\"2\"")
            .replace("<escrowChanges/>", "");
    Files.writeString(file, old);
    var loaded = store.read(root);
    assertTrue(loaded.scenario().escrowEntries().isEmpty());
    assertEquals(
        n("600.00"),
        MortgageCalculator.analyze(loaded.loan(), loaded.scenario(), List.of())
            .housing()
            .escrowTotal());
    assertEquals(old, Files.readString(file));
    String damaged = old.replace("schemaVersion=\"2\"", "schemaVersion=\"3\"");
    Files.writeString(file, damaged);
    assertThrows(StorageException.class, () -> store.read(root));
    assertEquals(damaged, Files.readString(file));
  }
}
