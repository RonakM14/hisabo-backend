package com.ledgerone.service;

import com.ledgerone.domain.Ledger.*;
import com.ledgerone.storage.XmlLedgerStore;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class LedgerService {
  private final XmlLedgerStore store;

  public LedgerService(XmlLedgerStore store) {
    this.store = store;
  }

  public Year add(int year, TransactionInput input) {
    validate(year, input);
    return store.update(
        year,
        input.revision(),
        ledger -> {
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
    var required = EnumSet.noneOf(Category.class);
    for (Category c : Category.values()) if (c.type == Type.EXPENSE) required.add(c);
    if (!input.budgets().keySet().equals(required))
      throw new IllegalArgumentException(
          "Provide all six expense categories, and no income categories.");
    return store.update(
        year,
        input.revision(),
        ledger -> {
          var budget = ledger.months().get(month - 1).budgets();
          budget.clear();
          input.budgets().forEach((c, v) -> budget.put(c, v.setScale(2)));
          return ledger;
        });
  }

  private static void validate(int year, TransactionInput input) {
    XmlLedgerStore.checkYear(year);
    if (input.date().getYear() != year)
      throw new IllegalArgumentException("The transaction date must be in the selected year.");
    if (input.type() != input.category().type)
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
