package com.hisabo.mortgage;

import com.hisabo.mortgage.Mortgage.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Escrow is a housing cost, never a principal payment or interest-saving benefit. */
public final class MortgageEscrow {
  private static final BigDecimal ZERO = new BigDecimal("0.00");

  private MortgageEscrow() {}

  public static void validate(List<EscrowChange> changes) {
    if (changes.size() > 1200)
      throw new IllegalArgumentException("Use at most 1,200 monthly escrow entries.");
    var months = new HashSet<String>();
    for (var entry : changes) {
      if (entry == null || entry.scope() == null)
        throw new IllegalArgumentException("Choose how each escrow amount applies.");
      try {
        YearMonth month = YearMonth.parse(entry.month());
        if (!month.toString().equals(entry.month())
            || month.getYear() < 1900
            || month.getYear() > 2100)
          throw new IllegalArgumentException("Escrow months must be between 1900 and 2100.");
      } catch (DateTimeException | NullPointerException e) {
        throw new IllegalArgumentException("Choose a valid month for each escrow amount.");
      }
      if (!months.add(entry.month()))
        throw new IllegalArgumentException(
            "Only one escrow amount can be set for each month. Edit the existing entry.");
      MortgageCalculator.amount(entry.amount(), false);
      if (entry.amount().compareTo(BigDecimal.valueOf(1000000)) > 0)
        throw new IllegalArgumentException("Monthly escrow cannot exceed 1,000,000.");
    }
  }

  public static EscrowChange source(List<EscrowChange> changes, String month) {
    EscrowChange source = null;
    for (var entry : changes) {
      if (entry.month().equals(month)) return entry;
      if (entry.scope() == EscrowScope.FROM_MONTH
          && entry.month().compareTo(month) < 0
          && (source == null || entry.month().compareTo(source.month()) > 0)) source = entry;
    }
    return source;
  }

  public static HousingProjection project(Loan loan, Scenario scenario, Schedule schedule) {
    var rows = new ArrayList<HousingMonth>();
    var annual = new TreeMap<Integer, HousingYear>();
    BigDecimal escrowTotal = ZERO, total = ZERO;
    for (var payment : schedule.payments()) {
      var source = source(scenario.escrowEntries(), YearMonth.from(payment.date()).toString());
      BigDecimal escrow =
          MortgageCalculator.cents(source == null ? loan.monthlyEscrow() : source.amount());
      BigDecimal regular = payment.payment().subtract(payment.extraPrincipal());
      BigDecimal housing = payment.payment().add(escrow);
      rows.add(
          new HousingMonth(
              payment.date(),
              regular,
              payment.extraPrincipal(),
              escrow,
              housing,
              payment.balance(),
              source == null ? null : source.month(),
              source == null ? null : source.scope()));
      escrowTotal = escrowTotal.add(escrow);
      total = total.add(housing);
      int year = payment.date().getYear();
      var previous = annual.getOrDefault(year, new HousingYear(year, 0, ZERO, ZERO, ZERO, ZERO));
      annual.put(
          year,
          new HousingYear(
              year,
              previous.payments() + 1,
              previous.principalAndInterest().add(regular),
              previous.extraPrincipal().add(payment.extraPrincipal()),
              previous.escrow().add(escrow),
              previous.totalPayment().add(housing)));
    }
    return new HousingProjection(
        escrowTotal, total, List.copyOf(rows), List.copyOf(annual.values()));
  }

  /** Older clients omit this new field. Preserve the saved schedule in that case. */
  public static Scenario preserveOmitted(Scenario input, Scenario saved) {
    return input.escrowChanges() != null
        ? input
        : new Scenario(
            input.monthlyExtra(),
            input.extraStartMonth(),
            input.oneTimeExtra(),
            input.oneTimeMonth(),
            input.oneTimePayments(),
            saved.escrowEntries());
  }
}
