package com.hisabo.mortgage;

import com.hisabo.mortgage.Mortgage.*;
import com.hisabo.profile.ExcelLedgerWriter;
import java.io.*;
import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public final class MortgageWorkbook {
  public String fileName(String name) {
    String annualName = new ExcelLedgerWriter().fileName(name, 2000);
    return annualName.substring(0, annualName.length() - 10) + "-mortgage.xlsx";
  }

  public Path write(Path directory, String name, Plan plan) throws IOException {
    Files.createDirectories(directory);
    Path temp = Files.createTempFile(directory, ".mortgage-book-", ".tmp");
    try (var book = new XSSFWorkbook()) {
      var header = book.createCellStyle();
      header.setFillForegroundColor(IndexedColors.DARK_TEAL.getIndex());
      header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
      var font = book.createFont();
      font.setBold(true);
      font.setColor(IndexedColors.WHITE.getIndex());
      header.setFont(font);
      var money = book.createCellStyle();
      money.setDataFormat(book.createDataFormat().getFormat("\"$\"#,##0.00;[Red](\"$\"#,##0.00)"));
      var date = book.createCellStyle();
      date.setDataFormat(book.createDataFormat().getFormat("mmm d, yyyy"));
      Sheet overview = book.createSheet("Mortgage overview");
      title(overview, "Hisabo · " + name + " · Mortgage", header, 5);
      text(
          overview,
          2,
          0,
          "USD · Snapshot from XML revision "
              + plan.revision()
              + ". Edit the plan in Hisabo; this workbook is an export.");
      if (plan.loan() == null)
        text(
            overview,
            4,
            0,
            "Complete mortgage setup in Hisabo to see your payoff analysis and schedules.");
      else {
        Loan l = plan.loan();
        Scenario s = plan.scenario();
        Analysis a = MortgageCalculator.analyze(l, s, plan.improvements());
        headers(
            overview,
            4,
            header,
            "Loan details",
            "Value",
            "",
            "Comparison",
            "Without extras",
            "With extras");
        Object[][] inputs = {
          {"Mortgage started", l.startDate()},
          {"Balance after payment on", l.balanceDate()},
          {"Outstanding principal", l.balance()},
          {"Annual rate (%)", l.annualRate()},
          {"Interest convention", l.interestConvention().name()},
          {"Monthly principal + interest", a.monthlyPayment()},
          {"Default monthly escrow", l.monthlyEscrow()},
          {"Monthly extra principal", s.monthlyExtra()},
          {"Monthly extras start", s.extraStartMonth()},
          {"One-time principal requested", a.oneTimeImpact().requested()},
          {"One-time principal applied", a.oneTimeImpact().applied()},
          {"Payment input method", l.paymentMode().name()},
          {"Remaining term input (months)", l.remainingMonths()}
        };
        int row = 5;
        for (Object[] pair : inputs) {
          text(overview, row, 0, pair[0].toString());
          value(overview, row++, 1, pair[1], money, date);
        }
        text(overview, 5, 3, "Payoff date");
        value(overview, 5, 4, a.baseline().payoffDate(), money, date);
        value(overview, 5, 5, a.accelerated().payoffDate(), money, date);
        text(overview, 6, 3, "Months remaining");
        value(overview, 6, 4, a.baseline().months(), money, date);
        value(overview, 6, 5, a.accelerated().months(), money, date);
        text(overview, 7, 3, "Future interest");
        value(overview, 7, 4, a.baseline().interest(), money, date);
        value(overview, 7, 5, a.accelerated().interest(), money, date);
        text(overview, 8, 3, "Total loan payments");
        value(overview, 8, 4, a.baseline().totalPaid(), money, date);
        value(overview, 8, 5, a.accelerated().totalPaid(), money, date);
        text(overview, 10, 3, "Interest saved");
        formula(overview, 10, 4, "E8-F8", money);
        text(overview, 11, 3, "Months saved");
        cell(overview, 11, 4).setCellFormula("E7-F7");
        text(overview, 13, 3, "Home improvement spending");
        value(overview, 13, 4, a.improvementTotal(), money, date);
        text(overview, 14, 3, "Extra principal applied");
        value(overview, 14, 4, a.accelerated().extraPrincipal(), money, date);
        text(overview, 16, 3, "Interest saved by one-time payments");
        value(overview, 16, 4, a.oneTimeImpact().interestSaved(), money, date);
        text(overview, 17, 3, "Months saved by one-time payments");
        value(overview, 17, 4, a.oneTimeImpact().monthsSaved(), money, date);
        text(overview, 18, 3, "Projected escrow through payoff");
        value(overview, 18, 4, a.housing().escrowTotal(), money, date);
        text(overview, 19, 3, "Projected housing payments");
        value(overview, 19, 4, a.housing().totalPayments(), money, date);
        cell(overview, 8, 1)
            .setCellStyle(
                book.createCellStyle()); // Annual rate is a percentage value, not currency.
        row = 22;
        for (String note : a.notes()) {
          text(overview, row, 0, note);
          overview.addMergedRegion(new CellRangeAddress(row, row, 0, 5));
          var wrap = book.createCellStyle();
          wrap.setWrapText(true);
          cell(overview, row, 0).setCellStyle(wrap);
          overview.getRow(row++).setHeightInPoints(32);
        }
        schedule(book, "Standard schedule", a.baseline(), header, money, date);
        schedule(book, "Extra principal schedule", a.accelerated(), header, money, date);
        Sheet payments = book.createSheet("One-time payments");
        title(payments, "One-time mortgage payments · " + name, header, 6);
        text(
            payments,
            2,
            0,
            "Modeled after the regular payment and monthly extra on the next scheduled due date."
                + " Amounts are capped at principal remaining.");
        headers(
            payments,
            4,
            header,
            "Entered date",
            "Modeled application",
            "Requested (USD)",
            "Applied principal",
            "Balance after",
            "Status",
            "Payment ID");
        int paymentRow = 5;
        for (OneTimePaymentResult item : a.oneTimeImpact().payments()) {
          value(payments, paymentRow, 0, item.date(), money, date);
          value(payments, paymentRow, 1, item.applicationDate(), money, date);
          value(payments, paymentRow, 2, item.requested(), money, date);
          value(payments, paymentRow, 3, item.applied(), money, date);
          value(payments, paymentRow, 4, item.balanceAfter(), money, date);
          text(payments, paymentRow, 5, item.status().name().replace('_', ' '));
          text(payments, paymentRow++, 6, item.id().toString());
        }
        text(payments, paymentRow + 1, 1, "Total");
        formula(payments, paymentRow + 1, 2, "SUM(C6:C" + Math.max(6, paymentRow) + ")", money);
        formula(payments, paymentRow + 1, 3, "SUM(D6:D" + Math.max(6, paymentRow) + ")", money);
        payments.createFreezePane(2, 5);
        payments.setAutoFilter(new CellRangeAddress(4, Math.max(5, paymentRow - 1), 0, 6));
        escrowSheets(book, a.housing(), s, header, money, date);
      }
      if (plan.homeSale() != null) homeSaleSheet(book, plan, header, money, date);
      Sheet costs = book.createSheet("Home improvements");
      title(costs, "Home improvement expenses · " + name, header, 3);
      text(
          costs,
          2,
          0,
          "Tracked separately from the mortgage. These expenses do not change the loan balance or"
              + " interest savings.");
      headers(costs, 4, header, "Date", "Project", "Description", "Amount (USD)");
      int row = 5;
      for (Improvement item :
          plan.improvements().stream().sorted(Comparator.comparing(Improvement::date)).toList()) {
        value(costs, row, 0, item.date(), money, date);
        text(costs, row, 1, item.project());
        text(costs, row, 2, item.description());
        value(costs, row++, 3, item.amount(), money, date);
      }
      text(costs, row + 1, 2, "Total spent");
      formula(costs, row + 1, 3, "SUM(D6:D" + Math.max(6, row) + ")", money);
      costs.setAutoFilter(new CellRangeAddress(4, Math.max(5, row - 1), 0, 3));
      costs.createFreezePane(0, 5);
      for (Sheet sheet : book) {
        sheet.setDisplayGridlines(false);
        for (int c = 0; c < sheet.getRow(0).getLastCellNum(); c++)
          sheet.setColumnWidth(c, 24 * 256);
      }
      overview.setColumnWidth(0, 34 * 256);
      overview.setColumnWidth(1, 32 * 256);
      overview.setColumnWidth(2, 4 * 256);
      overview.setColumnWidth(3, 34 * 256);
      costs.setColumnWidth(1, 28 * 256);
      costs.setColumnWidth(2, 64 * 256);
      if (book.getSheet("Home sale estimate") != null) {
        book.getSheet("Home sale estimate").setColumnWidth(0, 42 * 256);
        book.getSheet("Home sale estimate").setColumnWidth(1, 32 * 256);
      }
      book.getCreationHelper().createFormulaEvaluator().evaluateAll();
      try (var out = Files.newOutputStream(temp)) {
        book.write(out);
      }
      try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
        channel.force(true);
      }
      Path target = directory.resolve(fileName(name));
      Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      return target;
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  private static void homeSaleSheet(
      Workbook book, Plan plan, CellStyle header, CellStyle money, CellStyle date) {
    var sheet = book.createSheet("Home sale estimate");
    var sale = plan.homeSale();
    var analysis = MortgageCalculator.analyze(plan.loan(), plan.scenario(), plan.improvements());
    var estimate = HomeSaleCalculator.estimate(sale, plan.loan(), analysis);
    title(sheet, "Home sale estimate · USD", header, 3);
    text(
        sheet,
        2,
        0,
        "Saved sale scenario. Tax reserve is manually entered; capital-gains tax is not"
            + " calculated.");
    headers(sheet, 4, header, "Item", "Value");
    Object[][] rows = {
      {"Expected closing date", sale.closingDate()},
      {"Selling price", sale.salePrice()},
      {"Mortgage payoff used", estimate == null ? null : estimate.mortgagePayoff()},
      {"Agent fee (%)", sale.agentFeePercent()},
      {"Agent fee (USD)", null},
      {"Other closing costs", sale.closingCosts()},
      {"Buyer credits", sale.buyerCredits()},
      {"Other secured loans", sale.otherLoans()},
      {"Cash before tax reserve", null},
      {"Apply tax reserve", sale.taxEnabled() ? "Yes" : "No"},
      {"Tax reserve entered", sale.taxReserve()},
      {"Tax reserve deducted", null},
      {"Cash in hand (negative = shortfall)", null}
    };
    for (int i = 0; i < rows.length; i++) {
      text(sheet, i + 5, 0, rows[i][0].toString());
      value(sheet, i + 5, 1, rows[i][1], money, date);
    }
    cell(sheet, 8, 1).setCellStyle(book.createCellStyle());
    formula(sheet, 9, 1, "ROUND(B7*B9/100,2)", money);
    formula(sheet, 16, 1, "IF(B15=\"Yes\",B16,0)", money);
    if (estimate != null) {
      formula(sheet, 13, 1, "B7-B8-SUM(B10:B13)", money);
      formula(sheet, 17, 1, "B14-B17", money);
      text(sheet, 19, 0, "Mortgage payoff source");
      text(
          sheet,
          19,
          1,
          sale.payoffOverride() == null ? "Projected principal" : "Entered lender quote");
      text(sheet, 20, 0, "Projected principal as of");
      value(sheet, 20, 1, estimate.balanceAsOf(), money, date);
    } else
      text(
          sheet,
          19,
          0,
          "Update the closing date in Hisabo: it precedes the latest mortgage balance date.");
    text(
        sheet,
        22,
        0,
        "Projected principal excludes daily payoff interest and lender fees. Use a lender quote for"
            + " closing.");
    text(
        sheet,
        23,
        0,
        "Escrow refunds are not included. Changes in Excel are not imported into Hisabo.");
    sheet.createFreezePane(1, 5);
  }

  private static void schedule(
      Workbook book,
      String name,
      Schedule schedule,
      CellStyle header,
      CellStyle money,
      CellStyle date) {
    Sheet sheet = book.createSheet(name);
    title(sheet, name, header, 8);
    headers(
        sheet,
        2,
        header,
        "Payment #",
        "Payment date",
        "Opening balance",
        "Total payment",
        "Interest",
        "Regular principal",
        "Extra principal",
        "Closing balance",
        "Interest to date");
    int r = 3;
    for (Payment p : schedule.payments()) {
      value(sheet, r, 0, p.number(), money, date);
      value(sheet, r, 1, p.date(), money, date);
      value(sheet, r, 2, p.openingBalance(), money, date);
      value(sheet, r, 3, p.payment(), money, date);
      value(sheet, r, 4, p.interest(), money, date);
      value(sheet, r, 5, p.scheduledPrincipal(), money, date);
      value(sheet, r, 6, p.extraPrincipal(), money, date);
      formula(sheet, r, 7, "MAX(0,C" + (r + 1) + "-F" + (r + 1) + "-G" + (r + 1) + ")", money);
      formula(sheet, r, 8, "SUM(E4:E" + (r + 1) + ")", money);
      r++;
    }
    sheet.createFreezePane(2, 3);
    sheet.setAutoFilter(new CellRangeAddress(2, Math.max(3, r - 1), 0, 8));
  }

  private static void escrowSheets(
      Workbook book,
      HousingProjection projection,
      Scenario scenario,
      CellStyle header,
      CellStyle money,
      CellStyle date) {
    Sheet monthly = book.createSheet("Monthly housing payments");
    title(monthly, "Monthly housing payments · projected from the balance snapshot", header, 7);
    text(
        monthly,
        1,
        0,
        "Escrow is separate from principal and interest. Taxes and insurance may continue after"
            + " payoff.");
    headers(
        monthly,
        3,
        header,
        "Payment date",
        "Principal + interest",
        "Extra principal",
        "Escrow",
        "Total payment",
        "Remaining balance",
        "Escrow source",
        "Applies to");
    int r = 4;
    for (var item : projection.months()) {
      value(monthly, r, 0, item.date(), money, date);
      value(monthly, r, 1, item.principalAndInterest(), money, date);
      value(monthly, r, 2, item.extraPrincipal(), money, date);
      value(monthly, r, 3, item.escrow(), money, date);
      formula(monthly, r, 4, "SUM(B" + (r + 1) + ":D" + (r + 1) + ")", money);
      value(monthly, r, 5, item.balance(), money, date);
      text(
          monthly,
          r,
          6,
          item.escrowSourceMonth() == null ? "Loan default" : item.escrowSourceMonth());
      text(
          monthly,
          r++,
          7,
          item.escrowScope() == null
              ? "Default"
              : item.escrowScope() == EscrowScope.FROM_MONTH ? "Carry forward" : "This month only");
    }
    monthly.createFreezePane(1, 4);
    monthly.setAutoFilter(new CellRangeAddress(3, Math.max(4, r - 1), 0, 7));
    Sheet yearly = book.createSheet("Yearly housing totals");
    title(yearly, "Yearly housing totals · remaining projected payments only", header, 5);
    headers(
        yearly,
        3,
        header,
        "Year",
        "Payments",
        "Principal + interest",
        "Extra principal",
        "Escrow",
        "Total payment");
    r = 4;
    for (var item : projection.years()) {
      value(yearly, r, 0, item.year(), money, date);
      value(yearly, r, 1, item.payments(), money, date);
      value(yearly, r, 2, item.principalAndInterest(), money, date);
      value(yearly, r, 3, item.extraPrincipal(), money, date);
      value(yearly, r, 4, item.escrow(), money, date);
      formula(yearly, r, 5, "SUM(C" + (r + 1) + ":E" + (r + 1) + ")", money);
      r++;
    }
    text(yearly, r + 1, 0, "Total");
    for (int c = 1; c <= 5; c++) {
      String column = Character.toString('A' + c);
      String formula = r == 4 ? "0" : "SUM(" + column + "5:" + column + r + ")";
      if (c == 1) cell(yearly, r + 1, c).setCellFormula(formula);
      else formula(yearly, r + 1, c, formula, money);
    }
    yearly.createFreezePane(1, 4);
    Sheet entries = book.createSheet("Escrow changes");
    title(entries, "Saved escrow amounts · edit in Hisabo", header, 2);
    headers(entries, 3, header, "Effective month", "Amount", "Applies to");
    r = 4;
    for (var item :
        scenario.escrowEntries().stream()
            .sorted(Comparator.comparing(EscrowChange::month))
            .toList()) {
      text(entries, r, 0, item.month());
      value(entries, r, 1, item.amount(), money, date);
      text(
          entries,
          r++,
          2,
          item.scope() == EscrowScope.FROM_MONTH ? "This month onward" : "This month only");
    }
    entries.createFreezePane(1, 4);
  }

  private static Cell cell(Sheet s, int r, int c) {
    Row row = s.getRow(r);
    if (row == null) row = s.createRow(r);
    Cell cell = row.getCell(c);
    return cell == null ? row.createCell(c) : cell;
  }

  private static void text(Sheet s, int r, int c, String text) {
    cell(s, r, c).setCellValue(text);
  }

  private static void value(Sheet s, int r, int c, Object value, CellStyle money, CellStyle date) {
    if (value == null) return;
    var cell = cell(s, r, c);
    if (value instanceof BigDecimal n) {
      cell.setCellValue(n.doubleValue());
      cell.setCellStyle(money);
    } else if (value instanceof Number n) cell.setCellValue(n.doubleValue());
    else if (value instanceof java.time.LocalDate d) {
      cell.setCellValue(d);
      cell.setCellStyle(date);
    } else cell.setCellValue(value.toString());
  }

  private static void formula(Sheet s, int r, int c, String f, CellStyle style) {
    var cell = cell(s, r, c);
    cell.setCellFormula(f);
    cell.setCellStyle(style);
  }

  private static void title(Sheet s, String text, CellStyle style, int end) {
    text(s, 0, 0, text);
    for (int c = 0; c <= end; c++) cell(s, 0, c).setCellStyle(style);
    s.addMergedRegion(new CellRangeAddress(0, 0, 0, end));
    s.getRow(0).setHeightInPoints(34);
  }

  private static void headers(Sheet s, int r, CellStyle style, String... labels) {
    for (int c = 0; c < labels.length; c++) {
      text(s, r, c, labels[c]);
      cell(s, r, c).setCellStyle(style);
    }
    s.getRow(r).setHeightInPoints(28);
  }
}
