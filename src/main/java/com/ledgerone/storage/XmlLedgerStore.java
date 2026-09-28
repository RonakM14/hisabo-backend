package com.ledgerone.storage;

import static java.nio.file.StandardOpenOption.*;

import com.ledgerone.domain.Ledger;
import com.ledgerone.domain.Ledger.*;
import java.io.*;
import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.UnaryOperator;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.w3c.dom.*;

/**
 * One authoritative XML file per year. All read/modify/write operations share a process and OS
 * lock.
 */
@Repository
public class XmlLedgerStore {
  private final Path directory;
  private final ReentrantLock mutex = new ReentrantLock(true);

  public XmlLedgerStore(@Value("${ledger.data-dir}") String directory) {
    this.directory = Path.of(directory).toAbsolutePath().normalize();
    try {
      Files.createDirectories(this.directory);
    } catch (IOException e) {
      throw new StorageException("Cannot create the ledger data directory.", e);
    }
  }

  public static void checkYear(int year) {
    if (year < 2000 || year > 2100)
      throw new IllegalArgumentException("Year must be between 2000 and 2100.");
  }

  public Year read(int year) {
    checkYear(year);
    return locked(() -> readUnlocked(year));
  }

  public List<Integer> years() {
    return locked(
        () -> {
          try (var files = Files.list(directory)) {
            return files
                .map(p -> p.getFileName().toString())
                .filter(n -> n.matches("ledger-\\d{4}\\.xml"))
                .map(n -> Integer.parseInt(n.substring(7, 11)))
                .sorted(Comparator.reverseOrder())
                .toList();
          }
        });
  }

  public Year update(int year, long expectedRevision, UnaryOperator<Year> action) {
    checkYear(year);
    return locked(
        () -> {
          Year previous = readUnlocked(year);
          if (previous.revision() != expectedRevision)
            throw new ConflictException(
                "This year changed in another window. Refresh and try again.");
          Year changed = action.apply(previous);
          Year next =
              new Year(
                  year,
                  previous.currency(),
                  previous.revision() + 1,
                  changed.demo(),
                  changed.months());
          writeAtomic(next);
          return next;
        });
  }

  public void seedIfEmpty(Year seed) {
    locked(
        () -> {
          try (var files = Files.list(directory)) {
            if (files.anyMatch(p -> p.getFileName().toString().matches("ledger-\\d{4}\\.xml")))
              return null;
          }
          writeAtomic(seed);
          return null;
        });
  }

  private Path file(int year) {
    return directory.resolve("ledger-" + year + ".xml");
  }

  private <T> T locked(IoSupplier<T> operation) {
    mutex.lock();
    try (var channel = FileChannel.open(directory.resolve(".ledger.lock"), CREATE, WRITE);
        var lock = channel.lock()) {
      return operation.get();
    } catch (IOException e) {
      throw new StorageException("Could not access the ledger. No changes were saved.", e);
    } finally {
      mutex.unlock();
    }
  }

  private Year readUnlocked(int year) {
    Path source = file(year);
    if (!Files.exists(source)) return Ledger.empty(year);
    try (InputStream stream = Files.newInputStream(source)) {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      Element root = factory.newDocumentBuilder().parse(stream).getDocumentElement();
      if (!root.getTagName().equals("ledger")
          || !root.getAttribute("schemaVersion").equals("1")
          || Integer.parseInt(root.getAttribute("year")) != year
          || !root.getAttribute("currency").equals("USD"))
        throw new IllegalArgumentException("Invalid ledger header");
      long revision = Long.parseLong(root.getAttribute("revision"));
      if (revision < 0) throw new IllegalArgumentException("Invalid revision");
      List<Month> months = new ArrayList<>();
      Set<Integer> seenMonths = new HashSet<>();
      Set<UUID> ids = new HashSet<>();
      for (Element month : children(root, "month")) {
        int number = Integer.parseInt(month.getAttribute("number"));
        if (number < 1 || number > 12 || !seenMonths.add(number))
          throw new IllegalArgumentException("Invalid month");
        Map<Category, BigDecimal> budgets = new EnumMap<>(Category.class);
        for (Element budget : children(single(month, "budgets"), "budget")) {
          Category category = Category.valueOf(budget.getAttribute("category"));
          if (category.type != Type.EXPENSE
              || budgets.put(category, amount(budget.getAttribute("amount"), false)) != null)
            throw new IllegalArgumentException("Invalid budget category");
        }
        if (budgets.size() != 6) throw new IllegalArgumentException("Missing expense budgets");
        List<Transaction> transactions = new ArrayList<>();
        for (Element tx : children(single(month, "transactions"), "transaction")) {
          UUID id = UUID.fromString(tx.getAttribute("id"));
          Type type = Type.valueOf(tx.getAttribute("type"));
          Category category = Category.valueOf(tx.getAttribute("category"));
          LocalDate date = LocalDate.parse(tx.getAttribute("date"));
          String description = single(tx, "description").getTextContent();
          if (!ids.add(id)
              || category.type != type
              || date.getYear() != year
              || date.getMonthValue() != number
              || description.isBlank()
              || description.length() > 120)
            throw new IllegalArgumentException("Invalid transaction");
          transactions.add(
              new Transaction(
                  id,
                  type,
                  description,
                  amount(tx.getAttribute("amount"), true),
                  category,
                  Account.valueOf(tx.getAttribute("account")),
                  date,
                  Instant.parse(tx.getAttribute("createdAt")),
                  Instant.parse(tx.getAttribute("updatedAt"))));
        }
        months.add(new Month(number, budgets, transactions));
      }
      if (months.size() != 12) throw new IllegalArgumentException("Expected twelve months");
      months.sort(Comparator.comparingInt(Month::number));
      return new Year(
          year, "USD", revision, Boolean.parseBoolean(root.getAttribute("demo")), months);
    } catch (Exception e) {
      throw new StorageException(
          "The XML for "
              + year
              + " could not be read. The original file is preserved; check it or restore its .bak"
              + " backup.",
          e);
    }
  }

  private static BigDecimal amount(String value, boolean positive) {
    BigDecimal n = new BigDecimal(value);
    if (n.scale() > 2
        || n.compareTo(BigDecimal.ZERO) < 0
        || (positive && n.signum() == 0)
        || n.compareTo(new BigDecimal("10000000")) > 0)
      throw new IllegalArgumentException("Invalid amount");
    return n.setScale(2);
  }

  private void writeAtomic(Year year) throws IOException {
    Path temp = Files.createTempFile(directory, ".ledger-", ".tmp");
    try {
      var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
      Element root = document.createElement("ledger");
      document.appendChild(root);
      attrs(
          root,
          Map.of(
              "schemaVersion",
              "1",
              "year",
              Integer.toString(year.year()),
              "currency",
              year.currency(),
              "revision",
              Long.toString(year.revision()),
              "demo",
              Boolean.toString(year.demo())));
      for (Month month : year.months()) {
        Element node = append(document, root, "month");
        attrs(
            node,
            Map.of(
                "number",
                Integer.toString(month.number()),
                "name",
                java.time.Month.of(month.number()).name()));
        Element budgets = append(document, node, "budgets");
        for (Category c : Category.values())
          if (c.type == Type.EXPENSE) {
            Element b = append(document, budgets, "budget");
            attrs(
                b,
                Map.of(
                    "category",
                    c.name(),
                    "amount",
                    month.budgets().get(c).setScale(2).toPlainString()));
          }
        Element transactions = append(document, node, "transactions");
        for (Transaction tx : month.transactions()) {
          Element t = append(document, transactions, "transaction");
          attrs(
              t,
              Map.of(
                  "id",
                  tx.id().toString(),
                  "type",
                  tx.type().name(),
                  "amount",
                  tx.amount().setScale(2).toPlainString(),
                  "category",
                  tx.category().name(),
                  "account",
                  tx.account().name(),
                  "date",
                  tx.date().toString(),
                  "createdAt",
                  tx.createdAt().toString(),
                  "updatedAt",
                  tx.updatedAt().toString()));
          append(document, t, "description").setTextContent(tx.description());
        }
      }
      TransformerFactory factory = TransformerFactory.newInstance();
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
      Transformer transformer = factory.newTransformer();
      transformer.setOutputProperty(OutputKeys.INDENT, "yes");
      transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
      transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
      try (var out = Files.newOutputStream(temp)) {
        transformer.transform(new DOMSource(document), new StreamResult(out));
      }
      try (var channel = FileChannel.open(temp, WRITE)) {
        channel.force(true);
      }
      Path target = file(year.year());
      if (Files.exists(target))
        Files.copy(
            target,
            directory.resolve(target.getFileName() + ".bak"),
            StandardCopyOption.REPLACE_EXISTING);
      // Refuse the save if this filesystem cannot atomically replace the XML file.
      Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw e;
    } catch (Exception e) {
      throw new StorageException("Could not serialize the ledger. No changes were saved.", e);
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  private static void attrs(Element node, Map<String, String> attrs) {
    attrs.forEach(node::setAttribute);
  }

  private static Element append(Document doc, Element parent, String name) {
    Element child = doc.createElement(name);
    parent.appendChild(child);
    return child;
  }

  private static List<Element> children(Element parent, String name) {
    List<Element> result = new ArrayList<>();
    for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling())
      if (n instanceof Element e && e.getTagName().equals(name)) result.add(e);
    return result;
  }

  private static Element single(Element parent, String name) {
    var nodes = children(parent, name);
    if (nodes.size() != 1) throw new IllegalArgumentException("Expected " + name);
    return nodes.getFirst();
  }

  @FunctionalInterface
  private interface IoSupplier<T> {
    T get() throws IOException;
  }

  public static class StorageException extends RuntimeException {
    public StorageException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  public static class ConflictException extends RuntimeException {
    public ConflictException(String message) {
      super(message);
    }
  }
}
