package com.hisabo.profile;

import com.hisabo.domain.Ledger.*;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.format.TextStyle;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/** Excel is a regenerated view of the authoritative XML ledger. */
@Component
public class ExcelLedgerWriter {
  public String fileName(String name, int year) {
    String safe = name.replaceAll("[^\\p{L}\\p{N} _.-]", "-").replaceAll("^[ .]+|[ .]+$", "");
    if (safe.isBlank()) safe = "Profile";
    return safe + "-" + year + ".xlsx";
  }

  public Path write(Path directory, String name, Year year) throws IOException {
    return write(directory, name, year, BudgetCategories.defaults());
  }

  public Path write(Path directory, String name, Year year, BudgetCategories.Catalog categories)
      throws IOException {
    Files.createDirectories(directory);
    Path target = directory.resolve(fileName(name, year.year()));
    Path temporary = Files.createTempFile(directory, ".workbook-", ".tmp");
    try (var book = new XSSFWorkbook()) {
      var title = style(book, true, IndexedColors.DARK_BLUE, IndexedColors.WHITE, 18);
      var header = style(book, true, IndexedColors.TEAL, IndexedColors.WHITE, 11);
      var money = book.createCellStyle();
      money.setDataFormat(
          book.createDataFormat().getFormat("\"$\"#,##0.00;[Red](\"$\"#,##0.00);\"$\"0.00"));
      var date = book.createCellStyle();
      date.setDataFormat(book.createDataFormat().getFormat("mmm d, yyyy"));
      Sheet summary = book.createSheet("Overview");
      banner(summary, "Hisabo · " + name + " · " + year.year(), title, 6);
      text(
          summary,
          2,
          0,
          "USD · XML revision "
              + year.revision()
              + " · Update transactions in Hisabo; Excel is an export.");
      headings(
          summary,
          4,
          header,
          "Month",
          "Income",
          "Expenses",
          "Net cash flow",
          "Budget",
          "Savings",
          "Budget shortfall");
      for (Month month : year.months()) {
        String label =
            java.time.Month.of(month.number()).getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        Sheet sheet = book.createSheet(label);
        banner(sheet, name + " · " + label + " " + year.year(), title, 7);
        text(
            sheet,
            1,
            0,
            "Savings = income not assigned to category budgets. "
                + (month.budgetOrigin() == BudgetOrigin.CARRIED
                    ? "Budget carried from " + month.budgetSource() + "."
                    : ""));
        headings(sheet, 2, header, "Category", "Budget", "Spent", "Remaining");
        var visible =
            categories.entries().stream()
                .filter(
                    c ->
                        c.active()
                            || month
                                    .budgets()
                                    .getOrDefault(c.id(), java.math.BigDecimal.ZERO)
                                    .signum()
                                != 0
                            || month.transactions().stream()
                                .anyMatch(t -> t.category().equals(c.id())))
                .toList();
        int budgetRow = 3;
        int totalsHeader = Math.max(10, budgetRow + visible.size() + 1);
        int totalsRow = totalsHeader + 1;
        int txHeader = totalsRow + 2;
        int firstTxRow = txHeader + 1;
        int firstTxExcelRow = firstTxRow + 1;
        int lastTxExcelRow = Math.max(firstTxExcelRow, firstTxRow + month.transactions().size());
        for (var category : visible) {
          text(sheet, budgetRow, 0, category.label() + (category.active() ? "" : " (removed)"));
          number(
              sheet,
              budgetRow,
              1,
              month.budgets().getOrDefault(category.id(), java.math.BigDecimal.ZERO).doubleValue(),
              money);
          // Match stable IDs (hidden column I), so arbitrary labels, including * and ?, are safe.
          text(sheet, budgetRow, 9, category.id());
          formula(
              sheet,
              budgetRow,
              2,
              "SUMIF(I"
                  + firstTxExcelRow
                  + ":I"
                  + lastTxExcelRow
                  + ",J"
                  + (budgetRow + 1)
                  + ",G"
                  + firstTxExcelRow
                  + ":G"
                  + lastTxExcelRow
                  + ")",
              money);
          formula(sheet, budgetRow, 3, "B" + (budgetRow + 1) + "-C" + (budgetRow + 1), money);
          budgetRow++;
        }
        headings(
            sheet,
            totalsHeader,
            header,
            "Income",
            "Expenses",
            "Net cash flow",
            "Budget",
            "Savings",
            "Budget shortfall");
        formula(
            sheet, totalsRow, 0, "SUM(F" + firstTxExcelRow + ":F" + lastTxExcelRow + ")", money);
        formula(
            sheet, totalsRow, 1, "SUM(G" + firstTxExcelRow + ":G" + lastTxExcelRow + ")", money);
        formula(sheet, totalsRow, 2, "A" + (totalsRow + 1) + "-B" + (totalsRow + 1), money);
        formula(sheet, totalsRow, 3, visible.isEmpty() ? "0" : "SUM(B4:B" + budgetRow + ")", money);
        formula(
            sheet, totalsRow, 4, "MAX(0,A" + (totalsRow + 1) + "-D" + (totalsRow + 1) + ")", money);
        formula(
            sheet, totalsRow, 5, "MAX(0,D" + (totalsRow + 1) + "-A" + (totalsRow + 1) + ")", money);
        headings(
            sheet,
            txHeader,
            header,
            "Date",
            "Description",
            "Type",
            "Category",
            "Account",
            "Income",
            "Expense",
            "Transaction ID");
        int row = firstTxRow;
        for (Transaction tx :
            month.transactions().stream()
                .sorted(
                    Comparator.comparing(Transaction::date).thenComparing(Transaction::createdAt))
                .toList()) {
          Cell dateCell = cell(sheet, row, 0);
          dateCell.setCellValue(tx.date());
          dateCell.setCellStyle(date);
          // Explicit string cells prevent a description beginning with '=' becoming a formula.
          text(sheet, row, 1, tx.description());
          text(sheet, row, 2, label(tx.type().name()));
          text(
              sheet,
              row,
              3,
              categories.entries().stream()
                  .filter(c -> c.id().equals(tx.category()))
                  .map(BudgetCategories.Entry::label)
                  .findFirst()
                  .orElseGet(() -> label(tx.category())));
          text(sheet, row, 8, tx.category());
          text(sheet, row, 4, label(tx.account().name()));
          number(sheet, row, 5, tx.type() == Type.INCOME ? tx.amount().doubleValue() : 0, money);
          number(sheet, row, 6, tx.type() == Type.EXPENSE ? tx.amount().doubleValue() : 0, money);
          text(sheet, row++, 7, tx.id().toString());
        }
        sheet.setAutoFilter(new CellRangeAddress(txHeader, Math.max(firstTxRow, row - 1), 0, 7));
        sheet.createFreezePane(2, firstTxRow);
        sheet.setColumnHidden(8, true);
        sheet.setColumnHidden(9, true);
        int[] widths = {22, 48, 19, 22, 20, 20, 20, 40};
        for (int c = 0; c < widths.length; c++) sheet.setColumnWidth(c, widths[c] * 256);
        sheet.setDisplayGridlines(false);
        sheet.getPrintSetup().setLandscape(true);
        text(summary, month.number() + 4, 0, label);
        for (int c = 1; c <= 6; c++) {
          formula(
              summary,
              month.number() + 4,
              c,
              "'" + label + "'!" + (char) ('A' + c - 1) + (totalsRow + 1),
              money);
        }
      }
      text(summary, 18, 0, "Annual total");
      for (int c = 1; c <= 6; c++) {
        char column = (char) ('A' + c);
        formula(summary, 18, c, "SUM(" + column + "6:" + column + "17)", money);
      }
      summary.setColumnWidth(0, 30 * 256);
      for (int c = 1; c <= 6; c++) summary.setColumnWidth(c, 24 * 256);
      summary.createFreezePane(1, 5);
      summary.setDisplayGridlines(false);
      book.getCreationHelper().createFormulaEvaluator().evaluateAll();
      book.setForceFormulaRecalculation(true);
      try (var out = Files.newOutputStream(temporary)) {
        book.write(out);
      }
      try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
        channel.force(true);
      }
      Files.move(
          temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      return target;
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static CellStyle style(
      Workbook book, boolean bold, IndexedColors background, IndexedColors foreground, int size) {
    var style = book.createCellStyle();
    style.setFillForegroundColor(background.getIndex());
    style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
    var font = book.createFont();
    font.setBold(bold);
    font.setColor(foreground.getIndex());
    font.setFontHeightInPoints((short) size);
    style.setFont(font);
    return style;
  }

  private static Cell cell(Sheet sheet, int r, int c) {
    Row row = sheet.getRow(r);
    if (row == null) row = sheet.createRow(r);
    Cell cell = row.getCell(c);
    return cell == null ? row.createCell(c) : cell;
  }

  private static void text(Sheet s, int r, int c, String value) {
    cell(s, r, c).setCellValue(value);
  }

  private static void number(Sheet s, int r, int c, double value, CellStyle style) {
    var cell = cell(s, r, c);
    cell.setCellValue(value);
    cell.setCellStyle(style);
  }

  private static void formula(Sheet s, int r, int c, String value, CellStyle style) {
    var cell = cell(s, r, c);
    cell.setCellFormula(value);
    cell.setCellStyle(style);
  }

  private static void headings(Sheet sheet, int row, CellStyle style, String... labels) {
    for (int c = 0; c < labels.length; c++) {
      text(sheet, row, c, labels[c]);
      cell(sheet, row, c).setCellStyle(style);
    }
    sheet.getRow(row).setHeightInPoints(26);
  }

  private static void banner(Sheet sheet, String value, CellStyle style, int lastColumn) {
    text(sheet, 0, 0, value);
    for (int c = 0; c <= lastColumn; c++) cell(sheet, 0, c).setCellStyle(style);
    sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, lastColumn));
    sheet.getRow(0).setHeightInPoints(40);
  }

  private static String label(String value) {
    return Arrays.stream(value.toLowerCase(Locale.ROOT).split("_"))
        .map(s -> Character.toUpperCase(s.charAt(0)) + s.substring(1))
        .reduce((a, b) -> a + " " + b)
        .orElse("");
  }
}
