package com.hisabo.mortgage;

import com.hisabo.mortgage.Mortgage.*;
import java.math.*;
import java.time.*;
import java.util.*;

public final class MortgageCalculator {
  private static final MathContext MC = MathContext.DECIMAL128;
  private static final BigDecimal ZERO = new BigDecimal("0.00");

  private MortgageCalculator() {}

  public static Analysis analyze(Loan loan, Scenario scenario, List<Improvement> improvements) {
    validate(loan, scenario);
    BigDecimal rate = monthlyRate(loan);
    BigDecimal payment;
    if (loan.paymentMode() == PaymentMode.PAYMENT) payment = cents(loan.monthlyPayment());
    else if (rate.signum() == 0)
      payment =
          loan.balance()
              .divide(BigDecimal.valueOf(loan.remainingMonths()), 2, RoundingMode.HALF_UP);
    else {
      BigDecimal growth = BigDecimal.ONE.add(rate).pow(loan.remainingMonths(), MC);
      payment =
          loan.balance()
              .multiply(rate, MC)
              .multiply(growth, MC)
              .divide(growth.subtract(BigDecimal.ONE), 2, RoundingMode.HALF_UP);
    }
    if (loan.balance().signum() > 0 && payment.compareTo(cents(loan.balance().multiply(rate))) <= 0)
      throw new IllegalArgumentException(
          "The monthly principal-and-interest payment must exceed the first month's interest. Check"
              + " the amount or remaining term.");
    Schedule baseline = schedule(loan, payment, rate, Scenario.empty(), new ArrayList<>());
    var paymentResults = new ArrayList<OneTimePaymentResult>();
    Schedule accelerated = schedule(loan, payment, rate, scenario, paymentResults);
    Schedule monthlyOnly =
        scenario.oneTimePayments().isEmpty() && scenario.oneTimeExtra().signum() == 0
            ? accelerated
            : schedule(
                loan,
                payment,
                rate,
                new Scenario(scenario.monthlyExtra(), scenario.extraStartMonth(), ZERO, null),
                new ArrayList<>());
    var impact =
        new OneTimeImpact(
            paymentResults.stream()
                .map(OneTimePaymentResult::requested)
                .reduce(ZERO, BigDecimal::add),
            paymentResults.stream()
                .map(OneTimePaymentResult::applied)
                .reduce(ZERO, BigDecimal::add),
            monthlyOnly.interest().subtract(accelerated.interest()),
            monthlyOnly.months() - accelerated.months(),
            List.copyOf(paymentResults));
    var notes = new ArrayList<String>();
    var housing = MortgageEscrow.project(loan, scenario, accelerated);
    notes.add(
        "Projection from the stated balance after a monthly payment; the next full monthly payment"
            + " is one month later. The rate and scheduled payment stay fixed.");
    notes.add(
        "Dated one-time payments are modeled on the next scheduled payment date on or after the"
            + " entered date, after the regular payment and monthly extra. This monthly estimate"
            + " does not model daily interest. Payments are capped at the remaining principal;"
            + " lender posting rules may differ.");
    notes.add(
        "One-time payment savings compare with the same plan including monthly extras, but without"
            + " one-time payments. The interest rate stays unchanged. Do not re-enter payments"
            + " already included in your balance snapshot.");
    notes.add(
        "Future interest only. Taxes, insurance, fees, prepayment penalties, rate changes, and home"
            + " improvements are excluded from loan interest and savings.");
    notes.add(
        "Monthly escrow changes affect projected housing payments, not principal, interest, or"
            + " payoff dates. Housing totals cover scheduled payments from the balance snapshot"
            + " through payoff, not recorded payment history. Taxes and insurance may continue"
            + " after payoff; they are not treated as savings.");
    if (paymentResults.stream().anyMatch(p -> p.status() == PaymentStatus.NOT_APPLIED))
      notes.add(
          "One or more one-time payments are not applied because the mortgage is already paid off"
              + " at their modeled application date.");
    if (scenario.monthlyExtra().signum() > 0
        && YearMonth.parse(scenario.extraStartMonth())
            .isAfter(YearMonth.from(accelerated.payoffDate())))
      notes.add("Monthly extra payments start after payoff, so they are not applied.");
    return new Analysis(
        payment,
        housing.months().isEmpty()
            ? ZERO
            : housing
                .months()
                .getFirst()
                .principalAndInterest()
                .add(housing.months().getFirst().escrow()),
        baseline,
        accelerated,
        baseline.interest().subtract(accelerated.interest()),
        baseline.months() - accelerated.months(),
        improvements.stream().map(Improvement::amount).reduce(ZERO, BigDecimal::add),
        impact,
        housing,
        notes);
  }

  private static Schedule schedule(
      Loan loan,
      BigDecimal payment,
      BigDecimal rate,
      Scenario scenario,
      List<OneTimePaymentResult> results) {
    var rows = new ArrayList<Payment>();
    BigDecimal balance = cents(loan.balance()), interestTotal = ZERO, paid = ZERO, extras = ZERO;
    YearMonth start =
        scenario.monthlyExtra().signum() > 0 ? YearMonth.parse(scenario.extraStartMonth()) : null;
    List<OneTimePayment> oneTime = oneTimePayments(loan, scenario);
    int nextOneTime = 0;
    for (int n = 1; balance.signum() > 0 && n <= 600; n++) {
      LocalDate date = loan.balanceDate().plusMonths(n);
      BigDecimal interest = cents(balance.multiply(rate));
      BigDecimal regular =
          loan.paymentMode() == PaymentMode.TERM && n == loan.remainingMonths()
              ? balance.add(interest)
              : payment.min(balance.add(interest));
      BigDecimal principal = regular.subtract(interest);
      BigDecimal requested = ZERO;
      if (start != null && !YearMonth.from(date).isBefore(start))
        requested = requested.add(scenario.monthlyExtra());
      BigDecimal extra = cents(requested).min(balance.subtract(principal));
      BigDecimal opening = balance;
      balance = balance.subtract(principal).subtract(extra);
      while (nextOneTime < oneTime.size() && !oneTime.get(nextOneTime).date().isAfter(date)) {
        var item = oneTime.get(nextOneTime++);
        BigDecimal applied = cents(item.amount()).min(balance);
        balance = balance.subtract(applied);
        extra = extra.add(applied);
        results.add(
            new OneTimePaymentResult(
                item.id(),
                item.date(),
                date,
                cents(item.amount()),
                applied,
                balance,
                applied.signum() == 0
                    ? PaymentStatus.NOT_APPLIED
                    : applied.compareTo(item.amount()) < 0
                        ? PaymentStatus.PARTIALLY_APPLIED
                        : PaymentStatus.APPLIED));
      }
      interestTotal = interestTotal.add(interest);
      paid = paid.add(regular).add(extra);
      extras = extras.add(extra);
      rows.add(
          new Payment(
              n,
              date,
              opening,
              regular.add(extra),
              interest,
              principal,
              extra,
              balance,
              interestTotal));
    }
    if (balance.signum() > 0)
      throw new IllegalArgumentException(
          "This loan would take more than 50 years to repay at that monthly payment. Enter a higher"
              + " payment or a shorter remaining term.");
    while (nextOneTime < oneTime.size()) {
      var item = oneTime.get(nextOneTime++);
      results.add(
          new OneTimePaymentResult(
              item.id(),
              item.date(),
              applicationDate(loan.balanceDate(), item.date()),
              cents(item.amount()),
              ZERO,
              ZERO,
              PaymentStatus.NOT_APPLIED));
    }
    return new Schedule(
        rows.size(),
        rows.isEmpty() ? loan.balanceDate() : rows.getLast().date(),
        interestTotal,
        paid,
        extras,
        List.copyOf(rows));
  }

  public static BigDecimal monthlyRate(Loan loan) {
    if (loan.interestConvention() == InterestConvention.CANADIAN_SEMIANNUAL)
      return BigDecimal.valueOf(Math.expm1(Math.log1p(loan.annualRate().doubleValue() / 200) / 6));
    return loan.annualRate().divide(BigDecimal.valueOf(1200), MC);
  }

  /** Preserve the old month-only entry on that month's anchored regular payment date. */
  public static List<OneTimePayment> oneTimePayments(Loan loan, Scenario scenario) {
    var payments = new ArrayList<>(scenario.oneTimePayments());
    if (scenario.oneTimeExtra().signum() > 0) {
      long months =
          java.time.temporal.ChronoUnit.MONTHS.between(
              YearMonth.from(loan.balanceDate()), YearMonth.parse(scenario.oneTimeMonth()));
      payments.add(
          new OneTimePayment(
              UUID.fromString("00000000-0000-0000-0000-000000000001"),
              loan.balanceDate().plusMonths(months),
              scenario.oneTimeExtra()));
    }
    return payments.stream().sorted(Comparator.comparing(OneTimePayment::date)).toList();
  }

  public static Scenario canonicalScenario(Loan loan, Scenario scenario) {
    return new Scenario(
        scenario.monthlyExtra(),
        scenario.extraStartMonth(),
        BigDecimal.ZERO,
        null,
        loan == null ? scenario.oneTimePayments() : oneTimePayments(loan, scenario),
        scenario.escrowEntries().stream()
            .sorted(Comparator.comparing(EscrowChange::month))
            .toList());
  }

  public static LocalDate applicationDate(LocalDate balanceDate, LocalDate paymentDate) {
    long months =
        Math.max(
            1,
            java.time.temporal.ChronoUnit.MONTHS.between(
                YearMonth.from(balanceDate), YearMonth.from(paymentDate)));
    LocalDate due = balanceDate.plusMonths(months);
    return due.isBefore(paymentDate) ? balanceDate.plusMonths(months + 1) : due;
  }

  public static void validate(Loan loan, Scenario scenario) {
    if (loan == null
        || scenario == null
        || loan.startDate() == null
        || loan.balanceDate() == null
        || loan.paymentMode() == null
        || loan.interestConvention() == null)
      throw new IllegalArgumentException(
          "Complete the mortgage dates, payment method, and interest convention.");
    if (loan.startDate().isAfter(loan.balanceDate()))
      throw new IllegalArgumentException(
          "Mortgage start date must be on or before the balance date.");
    checkDate(loan.startDate());
    checkDate(loan.balanceDate());
    amount(loan.balance(), false);
    amount(loan.monthlyEscrow(), false);
    if (loan.monthlyEscrow().compareTo(BigDecimal.valueOf(1000000)) > 0)
      throw new IllegalArgumentException("Monthly escrow cannot exceed 1,000,000.");
    MortgageEscrow.validate(scenario.escrowEntries());
    if (loan.annualRate() == null
        || loan.annualRate().signum() < 0
        || loan.annualRate().compareTo(BigDecimal.valueOf(30)) > 0
        || loan.annualRate().scale() > 6)
      throw new IllegalArgumentException(
          "Interest rate must be between 0 and 30 percent, with at most six decimals.");
    if (loan.paymentMode() == PaymentMode.PAYMENT) amount(loan.monthlyPayment(), true);
    if (loan.paymentMode() == PaymentMode.TERM
        && (loan.remainingMonths() == null
            || loan.remainingMonths() < 1
            || loan.remainingMonths() > 600))
      throw new IllegalArgumentException("Remaining term must be between 1 and 600 months.");
    amount(scenario.monthlyExtra(), false);
    amount(scenario.oneTimeExtra(), false);
    YearMonth next = YearMonth.from(loan.balanceDate().plusMonths(1));
    checkMonth(scenario.extraStartMonth(), scenario.monthlyExtra(), next);
    checkMonth(scenario.oneTimeMonth(), scenario.oneTimeExtra(), next);
    if (scenario.oneTimePayments().size() + (scenario.oneTimeExtra().signum() > 0 ? 1 : 0) > 500)
      throw new IllegalArgumentException(
          "A mortgage plan can contain up to 500 one-time payments.");
    var ids = new HashSet<UUID>();
    for (var item : scenario.oneTimePayments()) {
      if (item == null || item.id() == null || !ids.add(item.id()))
        throw new IllegalArgumentException("Each one-time payment must have a unique ID.");
      checkDate(item.date());
      amount(item.amount(), true);
      if (!item.date().isAfter(loan.balanceDate())
          || item.date().isAfter(loan.balanceDate().plusMonths(600)))
        throw new IllegalArgumentException(
            "One-time payment dates must be after the balance date and within the next 50 years."
                + " Payments already reflected in the balance must not be added again.");
    }
    if (scenario.oneTimeExtra().signum() > 0
        && ids.contains(UUID.fromString("00000000-0000-0000-0000-000000000001")))
      throw new IllegalArgumentException(
          "The legacy one-time payment duplicates a dated payment. Remove the legacy amount.");
  }

  public static void checkDate(LocalDate date) {
    if (date == null || date.getYear() < 1900 || date.getYear() > 2100)
      throw new IllegalArgumentException("Dates must be between 1900 and 2100.");
  }

  private static void checkMonth(String value, BigDecimal amount, YearMonth next) {
    if (amount.signum() == 0 && (value == null || value.isBlank())) return;
    try {
      YearMonth month = YearMonth.parse(value);
      if (month.isBefore(next) || month.isAfter(next.plusMonths(599)))
        throw new IllegalArgumentException(
            "Extra payment months must be within the next 50 years, starting with the next"
                + " payment.");
    } catch (DateTimeException | NullPointerException e) {
      throw new IllegalArgumentException("Choose a valid month for each extra principal payment.");
    }
  }

  public static void amount(BigDecimal amount, boolean positive) {
    if (amount == null
        || amount.scale() > 2
        || amount.signum() < 0
        || (positive && amount.signum() == 0)
        || amount.compareTo(BigDecimal.valueOf(100000000)) > 0)
      throw new IllegalArgumentException(
          "Enter an amount between "
              + (positive ? "0.01" : "0")
              + " and 100,000,000, with at most two decimal places.");
  }

  public static BigDecimal cents(BigDecimal value) {
    return value.setScale(2, RoundingMode.HALF_UP);
  }
}
