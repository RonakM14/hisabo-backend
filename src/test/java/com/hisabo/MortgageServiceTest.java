package com.hisabo;

import static org.junit.jupiter.api.Assertions.*;

import com.hisabo.mortgage.*;
import com.hisabo.mortgage.Mortgage.*;
import com.hisabo.profile.*;
import com.hisabo.profile.ProfileService.Template;
import com.hisabo.storage.XmlLedgerStore;
import com.hisabo.storage.XmlLedgerStore.*;
import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDate;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MortgageServiceTest {
  @TempDir Path root;

  ProfileService profiles() {
    return new ProfileService(
        root.toString(), new XmlLedgerStore(root.toString()), new ExcelLedgerWriter());
  }

  @Test
  void templateAndPlanPersistSeparatelyPreviewDoesNotSaveAndRenameUpdatesWorkbook()
      throws Exception {
    var profiles = profiles();
    var home = profiles.create("Our Home", Template.MORTGAGE);
    var other = profiles.create("Other Home", Template.MORTGAGE);
    assertEquals(Template.MORTGAGE, home.template());
    assertEquals(Template.BUDGET, profiles.profiles().getFirst().template());
    var service = new MortgageService(profiles);
    assertNull(service.read(home.id()).loan());
    var loan = MortgageCalculatorTest.loan("1000", "12", "500");
    var scenario = Scenario.empty();
    service.preview(home.id(), new PreviewInput(loan, scenario));
    assertNull(service.read(home.id()).loan());
    var saved = service.save(home.id(), new PlanInput(0L, loan, scenario));
    assertEquals(1, saved.revision());
    assertNull(saved.workbookWarning());
    var newService = new MortgageService(profiles());
    assertEquals(loan, newService.read(home.id()).loan());
    assertNull(newService.read(other.id()).loan());
    assertThrows(
        ConflictException.class,
        () -> newService.save(home.id(), new PlanInput(0L, loan, scenario)));
    var expense =
        newService.addImprovement(
            home.id(),
            new ImprovementInput(
                1L,
                LocalDate.of(2026, 3, 1),
                "Kitchen & extension",
                "=NOT_A_FORMULA",
                new BigDecimal("456.78")));
    assertEquals(new BigDecimal("456.78"), expense.analysis().improvementTotal());
    assertEquals(saved.analysis().accelerated(), expense.analysis().accelerated());
    try (var book =
        new XSSFWorkbook(new ByteArrayInputStream(service.download(home.id()).bytes()))) {
      assertEquals(8, book.getNumberOfSheets());
      assertNotNull(book.getSheet("Extra principal schedule"));
      assertEquals(
          CellType.STRING, book.getSheet("Home improvements").getRow(5).getCell(2).getCellType());
      assertEquals(
          456.78, book.getSheet("Home improvements").getRow(5).getCell(3).getNumericCellValue());
      assertEquals(
          0, book.getSheet("Standard schedule").getRow(5).getCell(7).getNumericCellValue());
    }
    profiles.rename(home.id(), "Family Home");
    Path folder = root.resolve("profiles").resolve(home.id()).resolve("excel");
    assertFalse(Files.exists(folder.resolve("Our Home-mortgage.xlsx")));
    assertTrue(Files.exists(folder.resolve("Family Home-mortgage.xlsx")));
    var id = expense.improvements().getFirst().id();
    var edited =
        service.saveImprovement(
            home.id(),
            id,
            new ImprovementInput(
                2L,
                LocalDate.of(2026, 3, 2),
                "Kitchen",
                "Corrected cost",
                new BigDecimal("123.45")));
    assertEquals(new BigDecimal("123.45"), edited.analysis().improvementTotal());
    var deleted = service.deleteImprovement(home.id(), id, 3);
    assertTrue(deleted.improvements().isEmpty());
    assertEquals(new BigDecimal("0.00"), deleted.analysis().improvementTotal());
    assertTrue(
        Files.exists(root.resolve("profiles").resolve(home.id()).resolve("mortgage.xml.bak")));
  }

  @Test
  void badXmlIsPreservedAndExportFailureStillConfirmsSavedPlan() throws Exception {
    var profiles = profiles();
    var home = profiles.create("House", Template.MORTGAGE);
    var service = new MortgageService(profiles);
    Path dir = root.resolve("profiles").resolve(home.id());
    Path file = dir.resolve("mortgage.xml");
    var original = Files.readAllBytes(file);
    String corrupt =
        "<!DOCTYPE mortgage [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><mortgage>&x;</mortgage>";
    Files.writeString(file, corrupt);
    assertThrows(StorageException.class, () -> service.read(home.id()));
    assertEquals(corrupt, Files.readString(file));
    Files.write(file, original);
    Path export = dir.resolve("excel/House-mortgage.xlsx");
    Files.delete(export);
    Files.createDirectory(export);
    var saved =
        service.save(
            home.id(),
            new PlanInput(0L, MortgageCalculatorTest.loan("1000", "12", "500"), Scenario.empty()));
    assertNotNull(saved.workbookWarning());
    assertEquals(1, new MortgageStore().read(dir).revision());
    Files.delete(export);
    assertNull(service.read(home.id()).workbookWarning());
  }
}
