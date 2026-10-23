package com.hisabo;

import static com.hisabo.MortgageCalculatorTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.hisabo.mortgage.*;
import com.hisabo.mortgage.Mortgage.*;
import com.hisabo.profile.*;
import com.hisabo.profile.ProfileService.Template;
import com.hisabo.storage.XmlLedgerStore;
import com.hisabo.storage.XmlLedgerStore.ConflictException;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MortgageOneTimePaymentsTest {
  @TempDir Path root;

  static OneTimePayment payment(String date, String amount) {
    return new OneTimePayment(UUID.randomUUID(), LocalDate.parse(date), n(amount));
  }

  static Scenario scenario(OneTimePayment... payments) {
    return new Scenario(n("0"), null, n("0"), null, List.of(payments));
  }

  @Test
  void multipleDatedPaymentsMatchHandCalculatedScheduleAndBalances() {
    var first = payment("2026-02-10", "100");
    var second = payment("2026-02-28", "50");
    var third = payment("2026-03-01", "200");
    var analysis =
        MortgageCalculator.analyze(
            loan("1000", "12", "200"), scenario(third, first, second), List.of());
    assertEquals(n("19.96"), analysis.accelerated().interest());
    assertEquals(n("1019.96"), analysis.accelerated().totalPaid());
    assertEquals(LocalDate.of(2026, 5, 31), analysis.accelerated().payoffDate());
    var impact = analysis.oneTimeImpact();
    assertEquals(n("350.00"), impact.applied());
    assertEquals(n("11.16"), impact.interestSaved());
    assertEquals(2, impact.monthsSaved());
    assertEquals(
        List.of(first.id(), second.id(), third.id()),
        impact.payments().stream().map(OneTimePaymentResult::id).toList());
    assertEquals(n("710.00"), impact.payments().get(0).balanceAfter());
    assertEquals(n("660.00"), impact.payments().get(1).balanceAfter());
    assertEquals(n("266.60"), impact.payments().get(2).balanceAfter());
    assertEquals(LocalDate.of(2026, 2, 28), impact.payments().get(0).applicationDate());
    assertEquals(LocalDate.of(2026, 3, 31), impact.payments().get(2).applicationDate());
    assertTrue(impact.payments().stream().allMatch(p -> p.status() == PaymentStatus.APPLIED));
  }

  @Test
  void cappedAndPostPayoffPaymentsNeverOverpayAndIsolateMonthlyExtraSavings() {
    var scenario =
        new Scenario(
            n("100"),
            "2026-02",
            n("0"),
            null,
            List.of(
                payment("2026-02-05", "100"),
                payment("2026-02-20", "500"),
                payment("2026-03-01", "600")));
    var analysis = MortgageCalculator.analyze(loan("1000", "12", "500"), scenario, List.of());
    var impact = analysis.oneTimeImpact();
    assertEquals(n("1200.00"), impact.requested());
    assertEquals(n("410.00"), impact.applied());
    assertEquals(n("510.00"), analysis.accelerated().extraPrincipal());
    assertEquals(n("1010.00"), analysis.accelerated().totalPaid());
    assertEquals(n("4.10"), impact.interestSaved());
    assertEquals(1, impact.monthsSaved());
    assertEquals(n("5.25"), analysis.interestSaved());
    assertEquals(PaymentStatus.PARTIALLY_APPLIED, impact.payments().get(1).status());
    assertEquals(n("310.00"), impact.payments().get(1).applied());
    assertEquals(PaymentStatus.NOT_APPLIED, impact.payments().get(2).status());
    assertEquals(n("0.00"), impact.payments().get(2).balanceAfter());
  }

  @Test
  void datesHonorDueDayMonthEndLeapYearAndYearBoundary() {
    assertEquals(
        LocalDate.of(2028, 2, 29),
        MortgageCalculator.applicationDate(LocalDate.of(2028, 1, 31), LocalDate.of(2028, 2, 29)));
    assertEquals(
        LocalDate.of(2028, 3, 31),
        MortgageCalculator.applicationDate(LocalDate.of(2028, 1, 31), LocalDate.of(2028, 3, 1)));
    assertEquals(
        LocalDate.of(2027, 1, 15),
        MortgageCalculator.applicationDate(LocalDate.of(2026, 11, 15), LocalDate.of(2026, 12, 16)));
    assertEquals(
        LocalDate.of(2026, 2, 15),
        MortgageCalculator.applicationDate(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 1, 16)));
    var zero =
        MortgageCalculator.analyze(
            loan("1200", "0", "100"), scenario(payment("2026-02-01", "300")), List.of());
    assertEquals(9, zero.accelerated().months());
    assertEquals(n("0.00"), zero.oneTimeImpact().interestSaved());
    assertEquals(3, zero.oneTimeImpact().monthsSaved());
    var paidOff =
        MortgageCalculator.analyze(
            loan("0", "0", "100"), scenario(payment("2026-02-01", "300")), List.of());
    assertEquals(n("0.00"), paidOff.oneTimeImpact().applied());
    assertEquals(PaymentStatus.NOT_APPLIED, paidOff.oneTimeImpact().payments().getFirst().status());
  }

  @Test
  void rejectsDuplicateIdsInvalidDatesAndAmountsBeforeSaving() {
    var loan = loan("1000", "12", "200");
    for (var payment :
        List.of(
            payment("2026-01-31", "50"),
            payment("2026-01-30", "50"),
            payment("2076-02-01", "50"),
            payment("2026-02-01", "0"),
            payment("2026-02-01", "-1"),
            payment("2026-02-01", "1.001"))) {
      assertThrows(
          IllegalArgumentException.class,
          () -> MortgageCalculator.analyze(loan, scenario(payment), List.of()));
    }
    var duplicate = payment("2026-02-01", "100");
    assertThrows(
        IllegalArgumentException.class,
        () -> MortgageCalculator.analyze(loan, scenario(duplicate, duplicate), List.of()));
    var tooMany =
        new Scenario(
            n("0"),
            null,
            n("0"),
            null,
            java.util.stream.IntStream.range(0, 501)
                .mapToObj(i -> payment("2026-02-01", "1"))
                .toList());
    assertThrows(
        IllegalArgumentException.class, () -> MortgageCalculator.analyze(loan, tooMany, List.of()));
  }

  @Test
  void savesEditsRemovesReloadsAndExportsPaymentsWithinOnlyTheirProfile() throws Exception {
    var profiles =
        new ProfileService(
            root.toString(), new XmlLedgerStore(root.toString()), new ExcelLedgerWriter());
    var home = profiles.create("One Time Home", Template.MORTGAGE);
    var other = profiles.create("Separate Home", Template.MORTGAGE);
    var service = new MortgageService(profiles);
    var loan = loan("1000", "12", "200");
    var first = payment("2026-02-10", "100");
    var second = payment("2026-03-01", "200");
    var saved = service.save(home.id(), new PlanInput(0L, loan, scenario(first, second)));
    assertTrue(saved.supportsDatedPayments());
    assertEquals(
        2, new MortgageService(profiles).read(home.id()).scenario().oneTimePayments().size());
    assertTrue(service.read(other.id()).scenario().oneTimePayments().isEmpty());
    assertThrows(
        ConflictException.class,
        () -> service.save(home.id(), new PlanInput(0L, loan, scenario(first))));
    try (var book =
        new XSSFWorkbook(new ByteArrayInputStream(service.download(home.id()).bytes()))) {
      var sheet = book.getSheet("One-time payments");
      assertEquals(
          LocalDate.of(2026, 2, 10),
          sheet.getRow(5).getCell(0).getLocalDateTimeCellValue().toLocalDate());
      assertEquals(
          LocalDate.of(2026, 2, 28),
          sheet.getRow(5).getCell(1).getLocalDateTimeCellValue().toLocalDate());
      assertEquals(100, sheet.getRow(5).getCell(3).getNumericCellValue());
      assertEquals(710, sheet.getRow(5).getCell(4).getNumericCellValue());
      assertEquals(300, sheet.getRow(8).getCell(3).getNumericCellValue());
      assertEquals(
          saved.analysis().oneTimeImpact().interestSaved().doubleValue(),
          book.getSheet("Mortgage overview").getRow(16).getCell(4).getNumericCellValue());
    }
    var edited = new OneTimePayment(first.id(), first.date(), n("150"));
    var next = service.save(home.id(), new PlanInput(1L, loan, scenario(edited)));
    assertEquals(List.of(edited), service.read(home.id()).scenario().oneTimePayments());
    assertEquals(n("150.00"), next.analysis().oneTimeImpact().applied());
    var removed = service.save(home.id(), new PlanInput(2L, loan, Scenario.empty()));
    assertEquals(removed.analysis().baseline(), removed.analysis().accelerated());
    assertTrue(
        Files.exists(root.resolve("profiles").resolve(home.id()).resolve("mortgage.xml.bak")));
  }

  @Test
  void readsLegacyMonthPaymentAndMigratesWithoutChangingItsProjection() throws Exception {
    var store = new MortgageStore();
    var loan = loan("1000", "12", "200");
    var legacy = new Scenario(n("0"), null, n("100"), "2026-02");
    store.write(root, new Plan(2L, loan, Scenario.empty(), List.of()));
    Path file = root.resolve("mortgage.xml");
    String xml =
        Files.readString(file)
            .replace("schemaVersion=\"4\"", "schemaVersion=\"1\"")
            .replace(
                "<scenario monthlyExtra=\"0\">",
                "<scenario monthlyExtra=\"0\" oneTimeExtra=\"100\" oneTimeMonth=\"2026-02\">");
    Files.writeString(file, xml);
    Plan migrated = store.read(root);
    assertEquals(
        LocalDate.of(2026, 2, 28), migrated.scenario().oneTimePayments().getFirst().date());
    assertEquals(
        MortgageCalculator.analyze(loan, legacy, List.of()).accelerated(),
        MortgageCalculator.analyze(loan, migrated.scenario(), List.of()).accelerated());
    assertEquals(xml, Files.readString(file)); // Reading preserves the original XML until a save.
    store.write(root, migrated);
    assertTrue(Files.readString(file).contains("schemaVersion=\"4\""));
    assertEquals(migrated, store.read(root));
    assertEquals(xml, Files.readString(root.resolve("mortgage.xml.bak")));
  }
}
