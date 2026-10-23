package com.hisabo.mortgage;

import com.hisabo.mortgage.Mortgage.*;
import java.math.BigDecimal;

/** Sale proceeds only. A manual tax reserve is not a capital-gains tax calculation. */
public final class HomeSaleCalculator {
  private HomeSaleCalculator() {}

  public static void validate(HomeSale sale) {
    if (sale == null) throw new IllegalArgumentException("Enter your home sale estimate.");
    MortgageCalculator.checkDate(sale.closingDate());
    MortgageCalculator.amount(sale.salePrice(), true);
    if (sale.payoffOverride() != null) MortgageCalculator.amount(sale.payoffOverride(), false);
    MortgageCalculator.amount(sale.agentFeePercent(), false);
    if (sale.agentFeePercent().compareTo(BigDecimal.valueOf(100)) > 0)
      throw new IllegalArgumentException("Agent fee must be between 0 and 100 percent.");
    MortgageCalculator.amount(sale.closingCosts(), false);
    MortgageCalculator.amount(sale.buyerCredits(), false);
    MortgageCalculator.amount(sale.otherLoans(), false);
    MortgageCalculator.amount(sale.taxReserve(), false);
  }

  public static HomeSaleEstimate estimate(HomeSale sale, Loan loan, Analysis analysis) {
    if (sale == null || loan == null || analysis == null) return null;
    validate(sale);
    // A later statement can make an older sale estimate stale. Keep its inputs for correction.
    if (sale.closingDate().isBefore(loan.balanceDate())) return null;
    var balance = loan.balance();
    var asOf = loan.balanceDate();
    for (var payment : analysis.accelerated().payments()) {
      if (payment.date().isAfter(sale.closingDate())) break;
      balance = payment.balance();
      asOf = payment.date();
    }
    var payoff = sale.payoffOverride() == null ? balance : sale.payoffOverride();
    var agent =
        MortgageCalculator.cents(
            sale.salePrice().multiply(sale.agentFeePercent()).movePointLeft(2));
    var costs = agent.add(sale.closingCosts()).add(sale.buyerCredits());
    var beforeTax = sale.salePrice().subtract(payoff).subtract(costs).subtract(sale.otherLoans());
    var reserve = sale.taxEnabled() ? sale.taxReserve() : BigDecimal.ZERO;
    return new HomeSaleEstimate(
        MortgageCalculator.cents(balance),
        asOf,
        MortgageCalculator.cents(payoff),
        agent,
        MortgageCalculator.cents(costs),
        MortgageCalculator.cents(beforeTax),
        MortgageCalculator.cents(reserve),
        MortgageCalculator.cents(beforeTax.subtract(reserve)));
  }
}
