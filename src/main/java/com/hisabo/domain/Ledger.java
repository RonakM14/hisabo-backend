package com.hisabo.domain;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

public final class Ledger {
  private Ledger() {}

  public enum Type {
    INCOME,
    EXPENSE
  }

  public enum Category {
    HOUSING(Type.EXPENSE),
    FOOD(Type.EXPENSE),
    TRANSPORT(Type.EXPENSE),
    SHOPPING(Type.EXPENSE),
    LIFESTYLE(Type.EXPENSE),
    UTILITIES(Type.EXPENSE),
    SALARY(Type.INCOME),
    FREELANCE(Type.INCOME),
    OTHER_INCOME(Type.INCOME);
    public final Type type;

    Category(Type type) {
      this.type = type;
    }
  }

  public enum Account {
    CHECKING,
    CREDIT,
    CASH,
    SAVINGS
  }

  public record Transaction(
      UUID id,
      Type type,
      String description,
      BigDecimal amount,
      Category category,
      Account account,
      LocalDate date,
      Instant createdAt,
      Instant updatedAt) {}

  public record Month(
      int number, Map<Category, BigDecimal> budgets, List<Transaction> transactions) {}

  public record Year(int year, String currency, long revision, boolean demo, List<Month> months) {}

  public record TransactionInput(
      @NotNull @PositiveOrZero Long revision,
      @NotNull Type type,
      @NotBlank @Size(max = 120) String description,
      @NotNull @DecimalMin("0.01") @DecimalMax("10000000.00") @Digits(integer = 8, fraction = 2)
          BigDecimal amount,
      @NotNull Category category,
      @NotNull Account account,
      @NotNull LocalDate date) {}

  public record BudgetInput(
      @NotNull @PositiveOrZero Long revision,
      @NotNull
          Map<
                  Category,
                  @NotNull @DecimalMin("0") @DecimalMax("10000000.00")
                  @Digits(integer = 8, fraction = 2) BigDecimal>
              budgets) {}

  public static Year empty(int year) {
    List<Month> months = new ArrayList<>();
    for (int i = 1; i <= 12; i++) {
      Map<Category, BigDecimal> budgets = new EnumMap<>(Category.class);
      for (Category c : Category.values())
        if (c.type == Type.EXPENSE) budgets.put(c, BigDecimal.ZERO.setScale(2));
      months.add(new Month(i, budgets, new ArrayList<>()));
    }
    return new Year(year, "USD", 0, false, months);
  }
}
