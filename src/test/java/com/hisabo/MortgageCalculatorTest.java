package com.hisabo;

import static org.junit.jupiter.api.Assertions.*;

import com.hisabo.mortgage.Mortgage.*;
import com.hisabo.mortgage.MortgageCalculator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class MortgageCalculatorTest {
  static BigDecimal n(String v) {
    return new BigDecimal(v);
  }

  static Loan loan(String balance, String rate, String payment) {
    return new Loan(
        LocalDate.of(2020, 1, 31),
        LocalDate.of(2026, 1, 31),
        n(balance),
        n(rate),
        InterestConvention.MONTHLY,
        PaymentMode.PAYMENT,
        n(payment),
        null,
        n("250"));
  }

  @Test
  void knownAmortizationPaysInterestThenPrincipalAndCapsFinalPayment() {
    var result =
        MortgageCalculator.analyze(
            loan("1000", "12", "500"), new Scenario(n("100"), "2026-02", n("0"), null), List.of());
    assertEquals(n("15.25"), result.baseline().interest());
    assertEquals(3, result.baseline().months());
    assertEquals(n("14.10"), result.accelerated().interest());
    assertEquals(n("1.15"), result.interestSaved());
    assertEquals(1, result.monthsSaved());
    assertEquals(n("414.10"), result.accelerated().payments().getLast().payment());
    assertEquals(n("0.00"), result.accelerated().payments().getLast().balance());
    assertEquals(LocalDate.of(2026, 2, 28), result.baseline().payments().getFirst().date());
    assertEquals(LocalDate.of(2026, 3, 31), result.baseline().payments().get(1).date());
    assertEquals(n("750.00"), result.monthlyHousingPayment());
  }

  @Test
  void zeroInterestAndCombinedExtrasNeverOverpay() {
    var zero =
        MortgageCalculator.analyze(
            loan("1200", "0", "100"), new Scenario(n("100"), "2026-02", n("0"), null), List.of());
    assertEquals(12, zero.baseline().months());
    assertEquals(6, zero.accelerated().months());
    assertEquals(n("0.00"), zero.interestSaved());
    var capped =
        MortgageCalculator.analyze(
            loan("1000", "12", "500"),
            new Scenario(n("100"), "2026-02", n("9999"), "2026-02"),
            List.of());
    assertEquals(1, capped.accelerated().months());
    assertEquals(n("510.00"), capped.accelerated().extraPrincipal());
    assertEquals(n("1010.00"), capped.accelerated().totalPaid());
  }

  @Test
  void aDelayedExtraStartsOnlyInItsChosenMonthAndHistoricalInterestIsNotInferred() {
    var result =
        MortgageCalculator.analyze(
            loan("1200", "0", "100"), new Scenario(n("100"), "2026-04", n("0"), null), List.of());
    assertEquals(n("0.00"), result.accelerated().payments().get(1).extraPrincipal());
    assertEquals(n("100.00"), result.accelerated().payments().get(2).extraPrincipal());
    assertEquals(7, result.accelerated().months());
    var late =
        MortgageCalculator.analyze(
            loan("1000", "12", "500"), new Scenario(n("0"), null, n("1000"), "2027-01"), List.of());
    assertEquals(late.baseline(), late.accelerated());
    assertTrue(late.notes().stream().anyMatch(s -> s.contains("not applied")));
  }

  @Test
  void remainingTermPaymentMatchesKnownMortgageAndRoundingFinishesOnTerm() {
    Loan term =
        new Loan(
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 1, 1),
            n("300000"),
            n("6"),
            InterestConvention.MONTHLY,
            PaymentMode.TERM,
            null,
            360,
            n("0"));
    var result = MortgageCalculator.analyze(term, Scenario.empty(), List.of());
    assertEquals(n("1798.65"), result.monthlyPayment());
    assertEquals(360, result.baseline().months());
    assertEquals(n("1500.00"), result.baseline().payments().getFirst().interest());
    assertEquals(n("298.65"), result.baseline().payments().getFirst().scheduledPrincipal());
    assertEquals(n("0.00"), result.baseline().payments().getLast().balance());
    assertEquals(result.baseline().totalPaid(), term.balance().add(result.baseline().interest()));
  }

  @Test
  void canadianConventionUsesEffectiveMonthlyRate() {
    Loan original = loan("100000", "6", "1000");
    Loan canadian =
        new Loan(
            original.startDate(),
            original.balanceDate(),
            original.balance(),
            original.annualRate(),
            InterestConvention.CANADIAN_SEMIANNUAL,
            original.paymentMode(),
            original.monthlyPayment(),
            null,
            original.monthlyEscrow());
    var result = MortgageCalculator.analyze(canadian, Scenario.empty(), List.of());
    assertEquals(n("493.86"), result.baseline().payments().getFirst().interest());
  }

  @Test
  void invalidAndNonAmortizingLoansFailWithoutInfiniteSchedules() {
    assertThrows(
        IllegalArgumentException.class,
        () -> MortgageCalculator.analyze(loan("1000", "12", "10"), Scenario.empty(), List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> MortgageCalculator.analyze(loan("100000", "0", "1"), Scenario.empty(), List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> MortgageCalculator.analyze(loan("1000", "-1", "500"), Scenario.empty(), List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            MortgageCalculator.analyze(
                loan("1000", "12", "500"),
                new Scenario(n("50"), "2026-01", n("0"), null),
                List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            MortgageCalculator.analyze(
                loan("1000", "12", "500"),
                new Scenario(n("0"), null, n("50"), "2026-99"),
                List.of()));
    var paid = MortgageCalculator.analyze(loan("0", "6", "500"), Scenario.empty(), List.of());
    assertEquals(0, paid.baseline().months());
    assertEquals(n("0.00"), paid.baseline().interest());
  }
}
