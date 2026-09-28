package com.hisabo.service;

import com.hisabo.domain.Ledger;
import com.hisabo.domain.Ledger.*;
import com.hisabo.storage.XmlLedgerStore;
import java.math.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class DemoData implements ApplicationRunner {
  private final XmlLedgerStore store;
  private final boolean seed;

  public DemoData(XmlLedgerStore store, @Value("${ledger.seed-demo}") boolean seed) {
    this.store = store;
    this.seed = seed;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (seed) store.seedIfEmpty(create(LocalDate.now()));
  }

  public static Year create(LocalDate today) {
    Year ledger = Ledger.empty(today.getYear());
    String[] amounts = {"1800", "850", "500", "650", "450", "350"};
    Category[] categories = {
      Category.HOUSING,
      Category.FOOD,
      Category.TRANSPORT,
      Category.SHOPPING,
      Category.LIFESTYLE,
      Category.UTILITIES
    };
    for (Month m : ledger.months()) {
      for (int i = 0; i < categories.length; i++)
        m.budgets().put(categories[i], new BigDecimal(amounts[i]));
      if (m.number() > today.getMonthValue()) continue;
      add(ledger, m, today, 1, "Monthly salary", Category.SALARY, "3600", Account.CHECKING);
      add(ledger, m, today, 1, "Apartment rent", Category.HOUSING, "1750", Account.CHECKING);
      add(
          ledger,
          m,
          today,
          3,
          "Whole Foods Market",
          Category.FOOD,
          scaled(118.4, m.number()),
          Account.CREDIT);
      add(ledger, m, today, 4, "Metro monthly pass", Category.TRANSPORT, "96", Account.CHECKING);
      add(ledger, m, today, 6, "Internet service", Category.UTILITIES, "75", Account.CHECKING);
      add(
          ledger,
          m,
          today,
          7,
          "Weekend brunch",
          Category.FOOD,
          scaled(64.8, m.number()),
          Account.CREDIT);
      add(
          ledger,
          m,
          today,
          9,
          "Nike running essentials",
          Category.SHOPPING,
          scaled(185, m.number()),
          Account.CREDIT);
      add(
          ledger,
          m,
          today,
          10,
          "Electricity",
          Category.UTILITIES,
          scaled(92.4, m.number()),
          Account.CHECKING);
      add(ledger, m, today, 12, "Local coffee shop", Category.FOOD, "18.50", Account.CASH);
      add(
          ledger,
          m,
          today,
          14,
          "Design consulting",
          Category.FREELANCE,
          scaled(850, m.number()),
          Account.CHECKING);
      add(ledger, m, today, 15, "Monthly salary", Category.SALARY, "3600", Account.CHECKING);
      add(
          ledger,
          m,
          today,
          16,
          "Trader Joe’s",
          Category.FOOD,
          scaled(134.25, m.number()),
          Account.CREDIT);
      add(ledger, m, today, 18, "Gym membership", Category.LIFESTYLE, "89", Account.CHECKING);
      add(
          ledger,
          m,
          today,
          19,
          "Uber rides",
          Category.TRANSPORT,
          scaled(54.8, m.number()),
          Account.CREDIT);
      add(
          ledger,
          m,
          today,
          21,
          "Dinner with friends",
          Category.FOOD,
          scaled(87.6, m.number()),
          Account.CREDIT);
      add(
          ledger,
          m,
          today,
          22,
          "Bookshop & stationery",
          Category.SHOPPING,
          scaled(78.9, m.number()),
          Account.CREDIT);
      add(
          ledger,
          m,
          today,
          24,
          "Live music tickets",
          Category.LIFESTYLE,
          scaled(120, m.number()),
          Account.CREDIT);
      add(
          ledger,
          m,
          today,
          25,
          "Weekly groceries",
          Category.FOOD,
          scaled(103.7, m.number()),
          Account.CREDIT);
      add(ledger, m, today, 26, "Fuel station", Category.TRANSPORT, "62.40", Account.CREDIT);
      add(ledger, m, today, 27, "Creative software", Category.LIFESTYLE, "32", Account.CHECKING);
    }
    return new Year(ledger.year(), "USD", 1, true, ledger.months());
  }

  private static String scaled(double amount, int month) {
    return BigDecimal.valueOf(amount * (.8 + (month % 5) * .085))
        .setScale(2, RoundingMode.HALF_UP)
        .toPlainString();
  }

  private static void add(
      Year year,
      Month month,
      LocalDate today,
      int day,
      String description,
      Category c,
      String amount,
      Account account) {
    LocalDate date = LocalDate.of(year.year(), month.number(), day);
    if (date.isAfter(today)) return;
    Instant at = date.atStartOfDay().toInstant(ZoneOffset.UTC);
    month
        .transactions()
        .add(
            new Transaction(
                UUID.randomUUID(),
                c.type,
                description,
                new BigDecimal(amount).setScale(2),
                c,
                account,
                date,
                at,
                at));
  }
}
