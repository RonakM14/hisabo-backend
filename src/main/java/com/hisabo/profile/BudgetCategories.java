package com.hisabo.profile;

import com.hisabo.domain.Ledger.*;
import com.hisabo.storage.XmlLedgerStore.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.text.Normalizer;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.*;

/** Profile-wide category settings. Call under the profile workspace lock. */
public final class BudgetCategories {
  public record Entry(
      @NotBlank String id,
      @NotBlank @Size(max = 40) String label,
      @NotBlank @Pattern(regexp = "#[0-9a-fA-F]{6}") String color,
      boolean active) {}

  public record Catalog(long revision, List<Entry> entries) {}

  public record Input(
      @NotNull @PositiveOrZero Long revision,
      @NotNull @Size(max = 200) List<@NotNull @Valid Entry> entries) {}

  public static Catalog defaults() {
    return new Catalog(
        0,
        List.of(
            new Entry("HOUSING", "Housing", "#4263eb", true),
            new Entry("FOOD", "Food & dining", "#8b6cef", true),
            new Entry("TRANSPORT", "Transport", "#34b4a4", true),
            new Entry("SHOPPING", "Shopping", "#f7b45a", true),
            new Entry("LIFESTYLE", "Lifestyle", "#e987a4", true),
            new Entry("UTILITIES", "Utilities", "#84afd2", true)));
  }

  public static Set<String> defaultIds() {
    return new LinkedHashSet<>(defaults().entries().stream().map(Entry::id).toList());
  }

  public static boolean isExpenseId(String id) {
    return id != null
        && (defaultIds().contains(id)
            || id.matches("CUSTOM_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
  }

  public static boolean matchesType(String id, Type type) {
    return type == Type.EXPENSE
        ? isExpenseId(id)
        : Set.of("SALARY", "FREELANCE", "OTHER_INCOME").contains(id);
  }

  public Catalog read(Path directory) {
    Path file = directory.resolve("budget-categories.xml");
    if (!Files.exists(file)) return defaults();
    try (var stream = Files.newInputStream(file)) {
      var factory = DocumentBuilderFactory.newInstance();
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      Element root = factory.newDocumentBuilder().parse(stream).getDocumentElement();
      if (!root.getTagName().equals("budgetCategories")
          || !root.getAttribute("schemaVersion").equals("1"))
        throw new IllegalArgumentException("Invalid category header");
      long revision = Long.parseLong(root.getAttribute("revision"));
      if (revision < 0) throw new IllegalArgumentException("Invalid revision");
      var entries = new ArrayList<Entry>();
      for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
        if (n instanceof Element e) {
          if (!e.getTagName().equals("category")
              || !Set.of("true", "false").contains(e.getAttribute("active")))
            throw new IllegalArgumentException("Invalid category");
          entries.add(
              new Entry(
                  e.getAttribute("id"),
                  e.getAttribute("label"),
                  e.getAttribute("color"),
                  Boolean.parseBoolean(e.getAttribute("active"))));
        }
      }
      return new Catalog(revision, validate(entries, defaults()));
    } catch (Exception e) {
      throw new StorageException(
          "Budget categories could not be read. The original file is preserved.", e);
    }
  }

  public Catalog save(Path directory, Input input) {
    Catalog previous = read(directory);
    if (input.revision() != previous.revision())
      throw new ConflictException(
          "Categories changed in another window. Close this dialog, refresh, and try again.");
    Catalog next = new Catalog(previous.revision() + 1, validate(input.entries(), previous));
    Path temporary = null;
    try {
      Files.createDirectories(directory);
      var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
      Element root = doc.createElement("budgetCategories");
      doc.appendChild(root);
      root.setAttribute("schemaVersion", "1");
      root.setAttribute("revision", Long.toString(next.revision()));
      for (Entry entry : next.entries()) {
        Element e = doc.createElement("category");
        e.setAttribute("id", entry.id());
        e.setAttribute("label", entry.label());
        e.setAttribute("color", entry.color());
        e.setAttribute("active", Boolean.toString(entry.active()));
        root.appendChild(e);
      }
      temporary = Files.createTempFile(directory, ".categories-", ".tmp");
      var factory = TransformerFactory.newInstance();
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
      var transformer = factory.newTransformer();
      transformer.setOutputProperty(OutputKeys.INDENT, "yes");
      transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
      try (var out = Files.newOutputStream(temporary)) {
        transformer.transform(new DOMSource(doc), new StreamResult(out));
      }
      try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
        channel.force(true);
      }
      Path target = directory.resolve("budget-categories.xml");
      if (Files.exists(target))
        Files.copy(
            target,
            directory.resolve("budget-categories.xml.bak"),
            StandardCopyOption.REPLACE_EXISTING);
      Files.move(
          temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      return next;
    } catch (Exception e) {
      throw new StorageException("Could not save budget categories. Please try again.", e);
    } finally {
      if (temporary != null)
        try {
          Files.deleteIfExists(temporary);
        } catch (java.io.IOException ignored) {
        }
    }
  }

  private List<Entry> validate(List<Entry> entries, Catalog previous) {
    if (entries == null || entries.size() > 200)
      throw new IllegalArgumentException(
          "A profile supports up to 200 categories, including removed categories.");
    var ids = new HashSet<String>();
    var names = new HashSet<String>();
    var result = new ArrayList<Entry>();
    for (Entry e : entries) {
      if (e == null || !isExpenseId(e.id()) || !ids.add(e.id()))
        throw new IllegalArgumentException("Invalid or repeated category ID.");
      String label =
          Normalizer.normalize(e.label() == null ? "" : e.label(), Normalizer.Form.NFKC).strip();
      if (label.isEmpty()
          || label.length() > 40
          || label
              .codePoints()
              .anyMatch(
                  c ->
                      Character.isISOControl(c)
                          || Character.getType(c) == Character.FORMAT
                          || c == 0xFFFE
                          || c == 0xFFFF))
        throw new IllegalArgumentException(
            "Category names must contain 1–40 characters without control characters.");
      if (!names.add(label.toLowerCase(Locale.ROOT)))
        throw new IllegalArgumentException(
            "Category names must be unique. Restore an existing category if needed.");
      if (e.color() == null || !e.color().matches("#[0-9a-fA-F]{6}"))
        throw new IllegalArgumentException("Choose a valid category color.");
      result.add(new Entry(e.id(), label, e.color().toLowerCase(Locale.ROOT), e.active()));
    }
    if (!ids.containsAll(previous.entries().stream().map(Entry::id).toList()))
      throw new IllegalArgumentException(
          "Keep existing categories with active=false so their history is preserved.");
    return List.copyOf(result);
  }
}
