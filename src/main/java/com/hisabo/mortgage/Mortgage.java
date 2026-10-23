package com.hisabo.mortgage;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class Mortgage {
  private Mortgage() {}

  public enum PaymentMode {
    PAYMENT,
    TERM
  }

  public enum InterestConvention {
    MONTHLY,
    CANADIAN_SEMIANNUAL
  }

  public record Loan(
      @NotNull LocalDate startDate,
      @NotNull LocalDate balanceDate,
      @NotNull @DecimalMin("0") @DecimalMax("100000000") @Digits(integer = 9, fraction = 2)
          BigDecimal balance,
      @NotNull @DecimalMin("0") @DecimalMax("30") @Digits(integer = 2, fraction = 6)
          BigDecimal annualRate,
      @NotNull InterestConvention interestConvention,
      @NotNull PaymentMode paymentMode,
      @DecimalMin("0.01") @DecimalMax("100000000") @Digits(integer = 9, fraction = 2)
          BigDecimal monthlyPayment,
      @Min(1) @Max(600) Integer remainingMonths,
      @NotNull @DecimalMin("0") @DecimalMax("1000000") @Digits(integer = 7, fraction = 2)
          BigDecimal monthlyEscrow) {}

  public record Scenario(
      @NotNull @DecimalMin("0") @DecimalMax("100000000") @Digits(integer = 9, fraction = 2)
          BigDecimal monthlyExtra,
      @Pattern(regexp = "\\d{4}-\\d{2}") String extraStartMonth,
      @DecimalMin("0") @DecimalMax("100000000") @Digits(integer = 9, fraction = 2)
          BigDecimal oneTimeExtra,
      @Pattern(regexp = "\\d{4}-\\d{2}") String oneTimeMonth,
      @Size(max = 500) List<@NotNull @Valid OneTimePayment> oneTimePayments,
      @Size(max = 1200) List<@NotNull @Valid EscrowChange> escrowChanges) {
    public Scenario {
      oneTimeExtra = oneTimeExtra == null ? BigDecimal.ZERO : oneTimeExtra;
      oneTimePayments = oneTimePayments == null ? List.of() : List.copyOf(oneTimePayments);
      if (escrowChanges != null) escrowChanges = List.copyOf(escrowChanges);
    }

    public Scenario(
        BigDecimal monthlyExtra,
        String extraStartMonth,
        BigDecimal oneTimeExtra,
        String oneTimeMonth,
        List<OneTimePayment> oneTimePayments) {
      this(monthlyExtra, extraStartMonth, oneTimeExtra, oneTimeMonth, oneTimePayments, List.of());
    }

    public List<EscrowChange> escrowEntries() {
      return escrowChanges == null ? List.of() : escrowChanges;
    }

    public Scenario(
        BigDecimal monthlyExtra,
        String extraStartMonth,
        BigDecimal oneTimeExtra,
        String oneTimeMonth) {
      this(monthlyExtra, extraStartMonth, oneTimeExtra, oneTimeMonth, List.of());
    }

    public static Scenario empty() {
      return new Scenario(BigDecimal.ZERO, null, BigDecimal.ZERO, null);
    }
  }

  public enum EscrowScope {
    FROM_MONTH,
    THIS_MONTH
  }

  public record EscrowChange(
      @NotNull @Pattern(regexp = "\\d{4}-\\d{2}") String month,
      @NotNull @DecimalMin("0") @DecimalMax("1000000") @Digits(integer = 7, fraction = 2)
          BigDecimal amount,
      @NotNull EscrowScope scope) {}

  public record HousingMonth(
      LocalDate date,
      BigDecimal principalAndInterest,
      BigDecimal extraPrincipal,
      BigDecimal escrow,
      BigDecimal totalPayment,
      BigDecimal balance,
      String escrowSourceMonth,
      EscrowScope escrowScope) {}

  public record HousingYear(
      int year,
      int payments,
      BigDecimal principalAndInterest,
      BigDecimal extraPrincipal,
      BigDecimal escrow,
      BigDecimal totalPayment) {}

  public record HousingProjection(
      BigDecimal escrowTotal,
      BigDecimal totalPayments,
      List<HousingMonth> months,
      List<HousingYear> years) {}

  public record OneTimePayment(
      @NotNull UUID id,
      @NotNull LocalDate date,
      @NotNull @DecimalMin("0.01") @DecimalMax("100000000") @Digits(integer = 9, fraction = 2)
          BigDecimal amount) {}

  public enum PaymentStatus {
    APPLIED,
    PARTIALLY_APPLIED,
    NOT_APPLIED
  }

  public record OneTimePaymentResult(
      UUID id,
      LocalDate date,
      LocalDate applicationDate,
      BigDecimal requested,
      BigDecimal applied,
      BigDecimal balanceAfter,
      PaymentStatus status) {}

  public record OneTimeImpact(
      BigDecimal requested,
      BigDecimal applied,
      BigDecimal interestSaved,
      int monthsSaved,
      List<OneTimePaymentResult> payments) {}

  public record Improvement(
      UUID id, LocalDate date, String project, String description, BigDecimal amount) {}

  public record HomeSale(
      @NotNull @DecimalMin("0.01") @DecimalMax("100000000") @Digits(integer = 9, fraction = 2)
          BigDecimal salePrice,
      @NotNull LocalDate closingDate,
      @DecimalMin("0") @DecimalMax("100000000") @Digits(integer = 9, fraction = 2)
          BigDecimal payoffOverride,
      @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2)
          BigDecimal agentFeePercent,
      @NotNull @DecimalMin("0") @DecimalMax("100000000") @Digits(integer = 9, fraction = 2)
          BigDecimal closingCosts,
      @NotNull @DecimalMin("0") @DecimalMax("100000000") @Digits(integer = 9, fraction = 2)
          BigDecimal buyerCredits,
      @NotNull @DecimalMin("0") @DecimalMax("100000000") @Digits(integer = 9, fraction = 2)
          BigDecimal otherLoans,
      boolean taxEnabled,
      @NotNull @DecimalMin("0") @DecimalMax("100000000") @Digits(integer = 9, fraction = 2)
          BigDecimal taxReserve) {}

  public record HomeSaleInput(
      @NotNull @PositiveOrZero Long revision, @NotNull @Valid HomeSale homeSale) {}

  public record HomeSaleEstimate(
      BigDecimal projectedBalance,
      LocalDate balanceAsOf,
      BigDecimal mortgagePayoff,
      BigDecimal agentFee,
      BigDecimal sellingCosts,
      BigDecimal cashBeforeTax,
      BigDecimal taxReserve,
      BigDecimal cashInHand) {}

  public record Plan(
      long revision,
      Loan loan,
      Scenario scenario,
      List<Improvement> improvements,
      HomeSale homeSale) {
    public Plan(long revision, Loan loan, Scenario scenario, List<Improvement> improvements) {
      this(revision, loan, scenario, improvements, null);
    }

    public static Plan empty() {
      return new Plan(0, null, Scenario.empty(), List.of());
    }
  }

  public record PlanInput(
      @NotNull @PositiveOrZero Long revision,
      @NotNull @Valid Loan loan,
      @NotNull @Valid Scenario scenario) {}

  public record PreviewInput(@NotNull @Valid Loan loan, @NotNull @Valid Scenario scenario) {}

  public record ImprovementInput(
      @NotNull @PositiveOrZero Long revision,
      @NotNull LocalDate date,
      @NotBlank @Size(max = 80) String project,
      @NotBlank @Size(max = 160) String description,
      @NotNull @DecimalMin("0.01") @DecimalMax("100000000") @Digits(integer = 9, fraction = 2)
          BigDecimal amount) {}

  public record Payment(
      int number,
      LocalDate date,
      BigDecimal openingBalance,
      BigDecimal payment,
      BigDecimal interest,
      BigDecimal scheduledPrincipal,
      BigDecimal extraPrincipal,
      BigDecimal balance,
      BigDecimal cumulativeInterest) {}

  public record Schedule(
      int months,
      LocalDate payoffDate,
      BigDecimal interest,
      BigDecimal totalPaid,
      BigDecimal extraPrincipal,
      List<Payment> payments) {}

  public record Analysis(
      BigDecimal monthlyPayment,
      BigDecimal monthlyHousingPayment,
      Schedule baseline,
      Schedule accelerated,
      BigDecimal interestSaved,
      int monthsSaved,
      BigDecimal improvementTotal,
      OneTimeImpact oneTimeImpact,
      HousingProjection housing,
      List<String> notes) {}

  public record View(
      long revision,
      Loan loan,
      Scenario scenario,
      List<Improvement> improvements,
      Analysis analysis,
      String fileName,
      String workbookWarning,
      boolean supportsDatedPayments,
      boolean supportsMonthlyEscrow,
      HomeSale homeSale,
      HomeSaleEstimate homeSaleEstimate,
      boolean supportsHomeSale) {}
}
