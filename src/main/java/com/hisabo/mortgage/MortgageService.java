package com.hisabo.mortgage;

import com.hisabo.mortgage.Mortgage.*;
import com.hisabo.profile.ProfileService;
import com.hisabo.profile.ProfileService.Download;
import com.hisabo.service.LedgerService.NotFoundException;
import com.hisabo.storage.XmlLedgerStore.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Service;

@Service
public class MortgageService {
  private final ProfileService profiles;
  private final MortgageStore store = new MortgageStore();
  private final MortgageWorkbook excel = new MortgageWorkbook();

  public MortgageService(ProfileService profiles) {
    this.profiles = profiles;
  }

  public View read(String id) {
    return profiles.withProfile(
        id, (directory, name) -> view(directory, name, store.read(directory)));
  }

  public Analysis preview(String id, PreviewInput input) {
    return profiles.withProfile(
        id,
        (directory, name) -> {
          Plan saved = store.read(directory);
          return MortgageCalculator.analyze(
              input.loan(),
              MortgageEscrow.preserveOmitted(input.scenario(), saved.scenario()),
              saved.improvements());
        });
  }

  public View save(String id, PlanInput input) {
    return update(
        id,
        input.revision(),
        old -> {
          Scenario scenario = MortgageEscrow.preserveOmitted(input.scenario(), old.scenario());
          MortgageCalculator.analyze(input.loan(), scenario, old.improvements());
          return new Plan(
              old.revision() + 1,
              input.loan(),
              MortgageCalculator.canonicalScenario(input.loan(), scenario),
              old.improvements(),
              old.homeSale());
        });
  }

  public View addImprovement(String id, ImprovementInput input) {
    return saveImprovement(id, null, input);
  }

  public View saveImprovement(String id, UUID itemId, ImprovementInput input) {
    Improvement item =
        new Improvement(
            itemId == null ? UUID.randomUUID() : itemId,
            input.date(),
            input.project().strip(),
            input.description().strip(),
            MortgageCalculator.cents(input.amount()));
    MortgageStore.validateImprovement(item);
    return update(
        id,
        input.revision(),
        old -> {
          var items = new ArrayList<>(old.improvements());
          if (itemId != null && !items.removeIf(e -> e.id().equals(itemId)))
            throw new NotFoundException("Home expense not found. Refresh and try again.");
          items.add(item);
          return new Plan(
              old.revision() + 1, old.loan(), old.scenario(), List.copyOf(items), old.homeSale());
        });
  }

  public View deleteImprovement(String id, UUID itemId, long revision) {
    return update(
        id,
        revision,
        old -> {
          var items = new ArrayList<>(old.improvements());
          if (!items.removeIf(e -> e.id().equals(itemId)))
            throw new NotFoundException("Home expense not found.");
          return new Plan(
              old.revision() + 1, old.loan(), old.scenario(), List.copyOf(items), old.homeSale());
        });
  }

  public View saveHomeSale(String id, HomeSaleInput input) {
    HomeSaleCalculator.validate(input.homeSale());
    return update(
        id,
        input.revision(),
        old -> {
          if (old.loan() == null)
            throw new IllegalArgumentException(
                "Save your mortgage details before creating a sale estimate.");
          if (input.homeSale().closingDate().isBefore(old.loan().balanceDate()))
            throw new IllegalArgumentException(
                "Closing date must be on or after your mortgage balance date.");
          return new Plan(
              old.revision() + 1, old.loan(), old.scenario(), old.improvements(), input.homeSale());
        });
  }

  public Download download(String id) {
    return profiles.withProfile(
        id,
        (directory, name) -> {
          try {
            Path path = excel.write(directory.resolve("excel"), name, store.read(directory));
            return new Download(path.getFileName().toString(), Files.readAllBytes(path));
          } catch (java.io.IOException e) {
            throw new StorageException(
                "Could not update the mortgage workbook. Close it in Excel and try again; your XML"
                    + " plan is safe.",
                e);
          }
        });
  }

  private View update(String id, long revision, UnaryOperator<Plan> action) {
    return profiles.withProfile(
        id,
        (directory, name) -> {
          Plan previous = store.read(directory);
          if (previous.revision() != revision)
            throw new ConflictException(
                "This mortgage plan changed in another window. Reload the saved plan before trying"
                    + " again.");
          Plan next = action.apply(previous);
          store.write(directory, next);
          return view(directory, name, next);
        });
  }

  private View view(Path directory, String name, Plan plan) {
    Analysis analysis =
        plan.loan() == null
            ? null
            : MortgageCalculator.analyze(plan.loan(), plan.scenario(), plan.improvements());
    String warning = null;
    try {
      excel.write(directory.resolve("excel"), name, plan);
    } catch (Exception e) {
      org.slf4j.LoggerFactory.getLogger(MortgageService.class)
          .warn("Mortgage workbook export failed", e);
      warning =
          "Your mortgage plan is saved in XML, but Excel could not update. Close the workbook and"
              + " retry Download Excel.";
    }
    return new View(
        plan.revision(),
        plan.loan(),
        plan.scenario(),
        plan.improvements(),
        analysis,
        excel.fileName(name),
        warning,
        true,
        true,
        plan.homeSale(),
        HomeSaleCalculator.estimate(plan.homeSale(), plan.loan(), analysis),
        true);
  }
}
