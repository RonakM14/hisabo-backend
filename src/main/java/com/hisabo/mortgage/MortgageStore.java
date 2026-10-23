package com.hisabo.mortgage;

import com.hisabo.mortgage.Mortgage.*;
import com.hisabo.storage.XmlLedgerStore.StorageException;
import java.io.*;
import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.*;

/** Access is serialized by ProfileService's process and OS workspace lock. */
public final class MortgageStore {
  public boolean exists(Path directory) {
    return Files.exists(directory.resolve("mortgage.xml"));
  }

  public Plan read(Path directory) {
    Path file = directory.resolve("mortgage.xml");
    if (!Files.exists(file))
      throw new IllegalArgumentException("This profile does not use the mortgage template.");
    try (var in = Files.newInputStream(file)) {
      var factory = DocumentBuilderFactory.newInstance();
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      Element root = factory.newDocumentBuilder().parse(in).getDocumentElement();
      if (!root.getTagName().equals("mortgage")
          || !Set.of("1", "2", "3", "4").contains(root.getAttribute("schemaVersion"))
          || !root.getAttribute("currency").equals("USD"))
        throw new IOException("Invalid mortgage file");
      long revision = Long.parseLong(root.getAttribute("revision"));
      if (revision < 0) throw new IOException("Invalid revision");
      Loan loan = null;
      var loans = root.getElementsByTagName("loan");
      if (loans.getLength() > 1) throw new IOException("Duplicate loan");
      if (loans.getLength() == 1) {
        Element e = (Element) loans.item(0);
        loan =
            new Loan(
                LocalDate.parse(e.getAttribute("startDate")),
                LocalDate.parse(e.getAttribute("balanceDate")),
                decimal(e, "balance"),
                decimal(e, "annualRate"),
                InterestConvention.valueOf(e.getAttribute("interestConvention")),
                PaymentMode.valueOf(e.getAttribute("paymentMode")),
                e.hasAttribute("monthlyPayment") ? decimal(e, "monthlyPayment") : null,
                e.hasAttribute("remainingMonths")
                    ? Integer.valueOf(e.getAttribute("remainingMonths"))
                    : null,
                decimal(e, "monthlyEscrow"));
      }
      var scenarios = root.getElementsByTagName("scenario");
      if (scenarios.getLength() != 1) throw new IOException("Missing scenario");
      Element e = (Element) scenarios.item(0);
      var oneTimePayments = new ArrayList<OneTimePayment>();
      var paymentNodes = e.getElementsByTagName("oneTimePayment");
      for (int i = 0; i < paymentNodes.getLength(); i++) {
        Element payment = (Element) paymentNodes.item(i);
        oneTimePayments.add(
            new OneTimePayment(
                UUID.fromString(payment.getAttribute("id")),
                LocalDate.parse(payment.getAttribute("date")),
                decimal(payment, "amount")));
      }
      boolean legacy = root.getAttribute("schemaVersion").equals("1");
      if (!legacy && e.getElementsByTagName("oneTimePayments").getLength() != 1)
        throw new IOException("Missing one-time payment list");
      var escrow = new ArrayList<EscrowChange>();
      var escrowContainers = e.getElementsByTagName("escrowChanges");
      if (Set.of("3", "4").contains(root.getAttribute("schemaVersion"))
          && escrowContainers.getLength() != 1) throw new IOException("Missing escrow schedule");
      if (escrowContainers.getLength() > 1) throw new IOException("Duplicate escrow schedule");
      var escrowNodes = e.getElementsByTagName("escrowChange");
      for (int i = 0; i < escrowNodes.getLength(); i++) {
        Element item = (Element) escrowNodes.item(i);
        escrow.add(
            new EscrowChange(
                item.getAttribute("month"),
                decimal(item, "amount"),
                EscrowScope.valueOf(item.getAttribute("scope"))));
      }
      MortgageEscrow.validate(escrow);
      Scenario scenario =
          new Scenario(
              decimal(e, "monthlyExtra"),
              optional(e, "extraStartMonth"),
              legacy ? decimal(e, "oneTimeExtra") : BigDecimal.ZERO,
              legacy ? optional(e, "oneTimeMonth") : null,
              oneTimePayments,
              escrow);
      var expenses = new ArrayList<Improvement>();
      var ids = new HashSet<UUID>();
      var nodes = root.getElementsByTagName("improvement");
      for (int i = 0; i < nodes.getLength(); i++) {
        Element n = (Element) nodes.item(i);
        UUID id = UUID.fromString(n.getAttribute("id"));
        if (!ids.add(id)) throw new IOException("Duplicate improvement");
        var expense =
            new Improvement(
                id,
                LocalDate.parse(n.getAttribute("date")),
                n.getAttribute("project"),
                n.getTextContent(),
                decimal(n, "amount"));
        validateImprovement(expense);
        expenses.add(expense);
      }
      if (loan != null) MortgageCalculator.analyze(loan, scenario, expenses);
      else if (!scenario.equals(Scenario.empty())
          && (scenario.monthlyExtra().signum() != 0
              || scenario.oneTimeExtra().signum() != 0
              || !scenario.oneTimePayments().isEmpty()
              || !scenario.escrowEntries().isEmpty()))
        throw new IOException("Scenario has no loan");
      HomeSale homeSale = null;
      var saleNodes = root.getElementsByTagName("homeSale");
      if (saleNodes.getLength() > 1) throw new IOException("Duplicate home sale estimate");
      if (saleNodes.getLength() == 1) {
        Element sale = (Element) saleNodes.item(0);
        if (loan == null || !Set.of("true", "false").contains(sale.getAttribute("taxEnabled")))
          throw new IOException("Invalid home sale estimate");
        homeSale =
            new HomeSale(
                decimal(sale, "salePrice"),
                LocalDate.parse(sale.getAttribute("closingDate")),
                sale.hasAttribute("payoffOverride") ? decimal(sale, "payoffOverride") : null,
                decimal(sale, "agentFeePercent"),
                decimal(sale, "closingCosts"),
                decimal(sale, "buyerCredits"),
                decimal(sale, "otherLoans"),
                Boolean.parseBoolean(sale.getAttribute("taxEnabled")),
                decimal(sale, "taxReserve"));
        HomeSaleCalculator.validate(homeSale);
      }
      return new Plan(
          revision,
          loan,
          MortgageCalculator.canonicalScenario(loan, scenario),
          List.copyOf(expenses),
          homeSale);
    } catch (Exception e) {
      throw new StorageException(
          "The mortgage XML could not be read. The original is preserved; check it or restore its"
              + " .bak backup.",
          e);
    }
  }

  public void write(Path directory, Plan plan) {
    Path temp = null;
    try {
      Files.createDirectories(directory);
      temp = Files.createTempFile(directory, ".mortgage-", ".tmp");
      var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
      Element root = document.createElement("mortgage");
      document.appendChild(root);
      root.setAttribute("schemaVersion", "4");
      root.setAttribute("revision", Long.toString(plan.revision()));
      root.setAttribute("currency", "USD");
      if (plan.loan() != null) {
        Loan l = plan.loan();
        Element e = child(document, root, "loan");
        attributes(
            e,
            "startDate",
            l.startDate(),
            "balanceDate",
            l.balanceDate(),
            "balance",
            l.balance(),
            "annualRate",
            l.annualRate(),
            "interestConvention",
            l.interestConvention(),
            "paymentMode",
            l.paymentMode(),
            "monthlyPayment",
            l.monthlyPayment(),
            "remainingMonths",
            l.remainingMonths(),
            "monthlyEscrow",
            l.monthlyEscrow());
      }
      Scenario s = MortgageCalculator.canonicalScenario(plan.loan(), plan.scenario());
      Element scenario = child(document, root, "scenario");
      attributes(
          scenario, "monthlyExtra", s.monthlyExtra(), "extraStartMonth", s.extraStartMonth());
      Element payments = child(document, scenario, "oneTimePayments");
      for (OneTimePayment item : s.oneTimePayments()) {
        Element e = child(document, payments, "oneTimePayment");
        attributes(e, "id", item.id(), "date", item.date(), "amount", item.amount());
      }
      Element escrow = child(document, scenario, "escrowChanges");
      for (EscrowChange item : s.escrowEntries()) {
        Element e = child(document, escrow, "escrowChange");
        attributes(e, "month", item.month(), "amount", item.amount(), "scope", item.scope());
      }
      if (plan.homeSale() != null) {
        var sale = plan.homeSale();
        HomeSaleCalculator.validate(sale);
        Element e = child(document, root, "homeSale");
        attributes(
            e,
            "salePrice",
            sale.salePrice(),
            "closingDate",
            sale.closingDate(),
            "payoffOverride",
            sale.payoffOverride(),
            "agentFeePercent",
            sale.agentFeePercent(),
            "closingCosts",
            sale.closingCosts(),
            "buyerCredits",
            sale.buyerCredits(),
            "otherLoans",
            sale.otherLoans(),
            "taxEnabled",
            sale.taxEnabled(),
            "taxReserve",
            sale.taxReserve());
      }
      Element expenses = child(document, root, "improvements");
      for (Improvement expense : plan.improvements()) {
        Element e = child(document, expenses, "improvement");
        attributes(
            e,
            "id",
            expense.id(),
            "date",
            expense.date(),
            "project",
            expense.project(),
            "amount",
            expense.amount());
        e.setTextContent(expense.description());
      }
      var factory = TransformerFactory.newInstance();
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
      var transformer = factory.newTransformer();
      transformer.setOutputProperty(OutputKeys.INDENT, "yes");
      transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
      try (var out = Files.newOutputStream(temp)) {
        transformer.transform(new DOMSource(document), new StreamResult(out));
      }
      try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
        channel.force(true);
      }
      Path target = directory.resolve("mortgage.xml");
      if (Files.exists(target))
        Files.copy(
            target, directory.resolve("mortgage.xml.bak"), StandardCopyOption.REPLACE_EXISTING);
      Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (Exception e) {
      throw new StorageException(
          "Could not save the mortgage plan. No mortgage changes were saved.", e);
    } finally {
      if (temp != null)
        try {
          Files.deleteIfExists(temp);
        } catch (IOException ignored) {
        }
    }
  }

  public static void validateImprovement(Improvement item) {
    MortgageCalculator.checkDate(item.date());
    MortgageCalculator.amount(item.amount(), true);
    validText(item.project(), 80);
    validText(item.description(), 160);
  }

  private static void validText(String value, int max) {
    if (value == null
        || value.isBlank()
        || value.length() > max
        || value.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException(
          "Enter a project and description without control characters, up to "
              + max
              + " characters.");
  }

  private static BigDecimal decimal(Element e, String key) {
    return new BigDecimal(e.getAttribute(key));
  }

  private static String optional(Element e, String key) {
    return e.hasAttribute(key) ? e.getAttribute(key) : null;
  }

  private static Element child(Document document, Element parent, String name) {
    Element e = document.createElement(name);
    parent.appendChild(e);
    return e;
  }

  private static void attributes(Element e, Object... pairs) {
    for (int i = 0; i < pairs.length; i += 2)
      if (pairs[i + 1] != null) e.setAttribute(pairs[i].toString(), pairs[i + 1].toString());
  }
}
