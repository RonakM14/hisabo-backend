package com.hisabo.service;

import com.hisabo.domain.Ledger.*;
import com.hisabo.profile.BudgetCategories;
import com.hisabo.storage.XmlLedgerStore;
import com.hisabo.storage.XmlLedgerStore.ConflictException;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class LedgerService {
  private final XmlLedgerStore store;

  private final BudgetCategories.Catalog categories;

  @Autowired
  public LedgerService(XmlLedgerStore store) {
    this(store, BudgetCategories.defaults());
  }

  public LedgerService(XmlLedgerStore store, BudgetCategories.Catalog categories) {
    this.store = store;
    this.categories = categories;
  }

  private void validateCategory(TransactionInput input, Transaction original) {
    if (input.type() == Type.INCOME) return;
    var category =
        categories.entries().stream()
            .filter(c -> c.id().equals(input.category()))
            .findFirst()
            .orElseThrow(
                () -> new IllegalArgumentException("Unknown expense category for this profile."));
    if (!category.active() && (original == null || !original.category().equals(input.category())))
      throw new ConflictException(
          "This category was removed. Refresh and choose an active category.");
  }

  public Year add(int year, TransactionInput input) {
    validate(year, input);
    return store.update(
        year,
        input.revision(),
        ledger -> {
          validateCategory(input, null);
          Instant now = Instant.now();
          ledger
              .months()
              .get(input.date().getMonthValue() - 1)
              .transactions()
              .add(
                  new Transaction(
                      UUID.randomUUID(),
                      input.type(),
                      input.description().strip(),
                      input.amount().setScale(2),
                      input.category(),
                      input.account(),
                      input.date(),
                      now,
                      now));
          return ledger;
        });
  }

  public Year edit(int year, UUID id, TransactionInput input) {
    validate(year, input);
    return store.update(
        year,
        input.revision(),
        ledger -> {
          Transaction original =
              ledger.months().stream()
                  .flatMap(m -> m.transactions().stream())
                  .filter(t -> t.id().equals(id))
                  .findFirst()
                  .orElseThrow(() -> new NotFoundException("Transaction not found."));
          validateCategory(input, original);
          ledger.months().forEach(m -> m.transactions().removeIf(t -> t.id().equals(id)));
          ledger
              .months()
              .get(input.date().getMonthValue() - 1)
              .transactions()
              .add(
                  new Transaction(
                      id,
                      input.type(),
                      input.description().strip(),
                      input.amount().setScale(2),
                      input.category(),
                      input.account(),
                      input.date(),
                      original.createdAt(),
                      Instant.now()));
          return ledger;
        });
  }

  public Year delete(int year, UUID id, long revision) {
    return store.update(
        year,
        revision,
        ledger -> {
          boolean found = false;
          for (Month m : ledger.months())
            found = m.transactions().removeIf(t -> t.id().equals(id)) || found;
          if (!found) throw new NotFoundException("Transaction not found.");
          return ledger;
        });
  }

  public Year budgets(int year, int month, BudgetInput input) {
    if (month < 1 || month > 12)
      throw new IllegalArgumentException("Month must be between 1 and 12.");
    var active =
        categories.entries().stream()
            .filter(BudgetCategories.Entry::active)
            .map(BudgetCategories.Entry::id)
            .collect(java.util.stream.Collectors.toSet());
    var known =
        categories.entries().stream()
            .map(BudgetCategories.Entry::id)
            .collect(java.util.stream.Collectors.toSet());
    if (!input.budgets().keySet().containsAll(active)
        || !known.containsAll(input.budgets().keySet()))
      throw new ConflictException(
          "Budget categories changed. Refresh and provide all active expense categories.");
    return store.update(
        year,
        input.revision(),
        ledger -> {
          var current = ledger.months().get(month - 1);
          var budget = new LinkedHashMap<>(current.budgets());
          input.budgets().forEach((c, v) -> budget.put(c, v.setScale(2)));
          ledger
              .months()
              .set(
                  month - 1,
                  new Month(month, budget, current.transactions(), BudgetOrigin.MANUAL, null));
          return ledger;
        });
  }

  private static void validate(int year, TransactionInput input) {
    XmlLedgerStore.checkYear(year);
    if (input.date().getYear() != year)
      throw new IllegalArgumentException("The transaction date must be in the selected year.");
    if (!BudgetCategories.matchesType(input.category(), input.type()))
      throw new IllegalArgumentException("The category does not match the transaction type.");
    if (input.description().codePoints().anyMatch(c -> c < 32 || c == 0xFFFE || c == 0xFFFF))
      throw new IllegalArgumentException("Description cannot contain control characters.");
  }

  public static class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
      super(message);
    }
  }
}
