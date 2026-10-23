package com.hisabo.api;

import com.hisabo.domain.Ledger.*;
import com.hisabo.profile.ProfileService;
import com.hisabo.profile.ProfileService.ProfileYear;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@Validated
public class LedgerController {
  private final ProfileService profiles;

  public LedgerController(ProfileService profiles) {
    this.profiles = profiles;
  }

  @GetMapping("/health")
  public Map<String, String> health() {
    return Map.of("status", "UP", "application", "Hisabo", "storage", "annual-xml");
  }

  @GetMapping("/years")
  public Map<String, Object> years() {
    return profiles.years("default");
  }

  @GetMapping("/years/{year}")
  public ProfileYear year(@PathVariable int year) {
    return profiles.year("default", year);
  }

  @PostMapping(value = "/years/{year}/transactions", consumes = "application/json")
  public ProfileYear add(@PathVariable int year, @Valid @RequestBody TransactionInput input) {
    return profiles.add("default", year, input);
  }

  @PutMapping(value = "/years/{year}/transactions/{id}", consumes = "application/json")
  public ProfileYear edit(
      @PathVariable int year, @PathVariable UUID id, @Valid @RequestBody TransactionInput input) {
    return profiles.edit("default", year, id, input);
  }

  @DeleteMapping("/years/{year}/transactions/{id}")
  public ProfileYear delete(
      @PathVariable int year, @PathVariable UUID id, @RequestParam @Min(0) long revision) {
    return profiles.delete("default", year, id, revision);
  }

  @PutMapping(value = "/years/{year}/months/{month}/budgets", consumes = "application/json")
  public ProfileYear budgets(
      @PathVariable int year, @PathVariable int month, @Valid @RequestBody BudgetInput input) {
    return profiles.budgets("default", year, month, input);
  }
}
