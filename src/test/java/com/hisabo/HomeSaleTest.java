package com.hisabo;

import static org.junit.jupiter.api.Assertions.*;

import com.hisabo.mortgage.*;
import com.hisabo.mortgage.Mortgage.*;
import com.hisabo.profile.*;
import com.hisabo.storage.XmlLedgerStore;
import com.hisabo.storage.XmlLedgerStore.ConflictException;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.List;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HomeSaleTest {
  @TempDir Path root;

  BigDecimal n(String value) {
    return new BigDecimal(value);
  }

  Loan loan() {
    return new Loan(
        LocalDate.parse("2020-01-01"),
        LocalDate.parse("2026-10-01"),
        n("1000"),
        n("0"),
        InterestConvention.MONTHLY,
        PaymentMode.PAYMENT,
        n("100"),
        null,
        n("50"));
  }

  HomeSale sale(String date, BigDecimal quote, boolean tax) {
    return new HomeSale(
        n("500000"),
        LocalDate.parse(date),
        quote,
        n("5"),
        n("5000"),
        n("1000"),
        n("10000"),
        tax,
        n("15000"));
  }

  @Test
  void usesOnlyPaymentsOnOrBeforeClosingAndAllowsZeroPayoffQuote() {
    var analysis = MortgageCalculator.analyze(loan(), Scenario.empty(), List.of());
    var before = HomeSaleCalculator.estimate(sale("2026-10-31", null, false), loan(), analysis);
    assertEquals(n("1000.00"), before.mortgagePayoff());
    var due = HomeSaleCalculator.estimate(sale("2026-11-01", null, true), loan(), analysis);
    assertEquals(n("900.00"), due.mortgagePayoff());
    assertEquals(n("25000.00"), due.agentFee());
    assertEquals(n("31000.00"), due.sellingCosts());
    assertEquals(n("458100.00"), due.cashBeforeTax());
    assertEquals(n("443100.00"), due.cashInHand());
    assertEquals(LocalDate.parse("2026-11-01"), due.balanceAsOf());
    assertEquals(
        n("0.00"),
        HomeSaleCalculator.estimate(sale("2028-01-01", null, false), loan(), analysis)
            .mortgagePayoff());
    assertEquals(
        n("0.00"),
        HomeSaleCalculator.estimate(sale("2026-11-01", n("0"), false), loan(), analysis)
            .mortgagePayoff());
    assertNull(HomeSaleCalculator.estimate(sale("2026-09-30", null, false), loan(), analysis));
  }

  @Test
  void roundsFeesToCentsAndKeepsShortfallsInsteadOfClampingToZero() {
    var input =
        new HomeSale(
            n("0.10"),
            loan().balanceDate(),
            n("1"),
            n("5"),
            n("0"),
            n("0"),
            n("0"),
            false,
            n("20"));
    var result =
        HomeSaleCalculator.estimate(
            input, loan(), MortgageCalculator.analyze(loan(), Scenario.empty(), List.of()));
    assertEquals(n("0.01"), result.agentFee());
    assertEquals(n("-0.91"), result.cashInHand());
    assertEquals(n("0.00"), result.taxReserve());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HomeSaleCalculator.validate(
                new HomeSale(
                    n("1"),
                    loan().balanceDate(),
                    null,
                    n("100.01"),
                    n("0"),
                    n("0"),
                    n("0"),
                    false,
                    n("0"))));
  }

  @Test
  void savedSaleSurvivesRestartLoanAndExpenseChangesAndExportsPerProfile() throws Exception {
    var profiles =
        new ProfileService(
            root.toString(), new XmlLedgerStore(root.toString()), new ExcelLedgerWriter());
    var home = profiles.create("Sale Home", ProfileService.Template.MORTGAGE);
    var other = profiles.create("Other Home", ProfileService.Template.MORTGAGE);
    var service = new MortgageService(profiles);
    service.save(home.id(), new PlanInput(0L, loan(), Scenario.empty()));
    var input = sale("2026-11-01", n("300000"), true);
    var saved = service.saveHomeSale(home.id(), new HomeSaleInput(1L, input));
    assertEquals(2L, saved.revision());
    assertEquals(input, new MortgageService(profiles).read(home.id()).homeSale());
    assertNull(service.read(other.id()).homeSale());
    assertThrows(
        ConflictException.class,
        () -> service.saveHomeSale(home.id(), new HomeSaleInput(1L, input)));
    assertNull(saved.workbookWarning());
    assertEquals(n("144000.00"), saved.homeSaleEstimate().cashInHand());
    assertEquals(
        input, service.save(home.id(), new PlanInput(2L, loan(), Scenario.empty())).homeSale());
    var expense =
        service.addImprovement(
            home.id(),
            new ImprovementInput(
                3L, LocalDate.parse("2026-10-05"), "Kitchen", "Cabinets", n("1000")));
    assertEquals(input, expense.homeSale());
    assertEquals(
        input,
        service
            .deleteImprovement(home.id(), expense.improvements().getFirst().id(), 4L)
            .homeSale());
    try (var workbook =
        new XSSFWorkbook(new ByteArrayInputStream(service.download(home.id()).bytes()))) {
      assertEquals(9, workbook.getNumberOfSheets());
      var sheet = workbook.getSheet("Home sale estimate");
      assertNotNull(sheet);
      assertEquals(144000, sheet.getRow(17).getCell(1).getNumericCellValue(), .001);
      assertEquals(25000, sheet.getRow(9).getCell(1).getNumericCellValue(), .001);
    }
    var newerLoan =
        new Loan(
            loan().startDate(),
            LocalDate.parse("2026-12-01"),
            n("800"),
            n("0"),
            loan().interestConvention(),
            PaymentMode.PAYMENT,
            n("100"),
            null,
            n("50"));
    var stale = service.save(home.id(), new PlanInput(5L, newerLoan, Scenario.empty()));
    assertNull(stale.homeSaleEstimate());
    assertNull(stale.workbookWarning());
    assertEquals(input, stale.homeSale());
  }

  @Test
  void olderXmlWithoutSaleStillLoadsAndInvalidSaleIsRejectedWithoutOverwriting() throws Exception {
    var store = new MortgageStore();
    store.write(root, new Plan(1L, loan(), Scenario.empty(), List.of()));
    var path = root.resolve("mortgage.xml");
    Files.writeString(
        path, Files.readString(path).replace("schemaVersion=\"4\"", "schemaVersion=\"3\""));
    assertNull(store.read(root).homeSale());
    assertEquals(loan(), store.read(root).loan());
    store.write(
        root, new Plan(2L, loan(), Scenario.empty(), List.of(), sale("2026-11-01", null, true)));
    var invalid = Files.readString(path).replace("taxEnabled=\"true\"", "taxEnabled=\"invalid\"");
    Files.writeString(path, invalid);
    assertThrows(XmlLedgerStore.StorageException.class, () -> store.read(root));
    assertEquals(invalid, Files.readString(path));
  }
}
