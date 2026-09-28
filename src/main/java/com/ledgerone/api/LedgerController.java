package com.ledgerone.api;

import com.ledgerone.domain.Ledger.*;
import com.ledgerone.service.LedgerService;
import com.ledgerone.storage.XmlLedgerStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.util.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@Validated
public class LedgerController {
  private final XmlLedgerStore store;
  private final LedgerService service;

  public LedgerController(XmlLedgerStore store, LedgerService service) {
    this.store = store;
    this.service = service;
  }

  @GetMapping("/health")
  public Map<String, String> health() {
    return Map.of("status", "UP", "application", "LedgerOne", "storage", "annual-xml");
  }

  @GetMapping("/years")
  public Map<String, Object> years() {
    var years = new TreeSet<Integer>(Comparator.reverseOrder());
    years.addAll(store.years());
    years.add(LocalDate.now().getYear());
    return Map.of("years", years, "currency", "USD");
  }

  @GetMapping("/years/{year}")
  public Year year(@PathVariable int year) {
    return store.read(year);
  }

  @PostMapping(value = "/years/{year}/transactions", consumes = "application/json")
  public Year add(@PathVariable int year, @Valid @RequestBody TransactionInput input) {
    return service.add(year, input);
  }

  @PutMapping(value = "/years/{year}/transactions/{id}", consumes = "application/json")
  public Year edit(
      @PathVariable int year, @PathVariable UUID id, @Valid @RequestBody TransactionInput input) {
    return service.edit(year, id, input);
  }

  @DeleteMapping("/years/{year}/transactions/{id}")
  public Year delete(
      @PathVariable int year, @PathVariable UUID id, @RequestParam @Min(0) long revision) {
    return service.delete(year, id, revision);
  }

  @PutMapping(value = "/years/{year}/months/{month}/budgets", consumes = "application/json")
  public Year budgets(
      @PathVariable int year, @PathVariable int month, @Valid @RequestBody BudgetInput input) {
    return service.budgets(year, month, input);
  }
}
