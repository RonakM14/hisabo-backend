package com.hisabo.api;

import com.hisabo.domain.Ledger.*;
import com.hisabo.profile.ProfileService;
import com.hisabo.profile.ProfileService.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/profiles")
@Validated
public class ProfileController {
  public record NameInput(@NotBlank @Size(max = 60) String name) {}

  public record CreateInput(@NotBlank @Size(max = 60) String name, Template template) {}

  private final ProfileService profiles;

  public ProfileController(ProfileService profiles) {
    this.profiles = profiles;
  }

  @GetMapping
  public List<Profile> list() {
    return profiles.profiles();
  }

  @PostMapping(consumes = "application/json")
  public Profile create(@Valid @RequestBody CreateInput input) {
    return profiles.create(input.name(), input.template());
  }

  @PutMapping(value = "/{profileId}", consumes = "application/json")
  public Profile rename(@PathVariable String profileId, @Valid @RequestBody NameInput input) {
    return profiles.rename(profileId, input.name());
  }

  @GetMapping("/{profileId}/years")
  public Map<String, Object> years(@PathVariable String profileId) {
    return profiles.years(profileId);
  }

  @GetMapping("/{profileId}/years/{year}")
  public ProfileYear year(@PathVariable String profileId, @PathVariable int year) {
    return profiles.year(profileId, year);
  }

  @PostMapping(value = "/{profileId}/years/{year}/transactions", consumes = "application/json")
  public ProfileYear add(
      @PathVariable String profileId,
      @PathVariable int year,
      @Valid @RequestBody TransactionInput input) {
    return profiles.add(profileId, year, input);
  }

  @PutMapping(value = "/{profileId}/years/{year}/transactions/{id}", consumes = "application/json")
  public ProfileYear edit(
      @PathVariable String profileId,
      @PathVariable int year,
      @PathVariable UUID id,
      @Valid @RequestBody TransactionInput input) {
    return profiles.edit(profileId, year, id, input);
  }

  @DeleteMapping("/{profileId}/years/{year}/transactions/{id}")
  public ProfileYear delete(
      @PathVariable String profileId,
      @PathVariable int year,
      @PathVariable UUID id,
      @RequestParam @Min(0) long revision) {
    return profiles.delete(profileId, year, id, revision);
  }

  @PutMapping(
      value = "/{profileId}/years/{year}/months/{month}/budgets",
      consumes = "application/json")
  public ProfileYear budgets(
      @PathVariable String profileId,
      @PathVariable int year,
      @PathVariable int month,
      @Valid @RequestBody BudgetInput input) {
    return profiles.budgets(profileId, year, month, input);
  }

  @PutMapping(value = "/{profileId}/budget-categories", consumes = "application/json")
  public ProfileYear categories(
      @PathVariable String profileId,
      @RequestParam int year,
      @Valid @RequestBody com.hisabo.profile.BudgetCategories.Input input) {
    return profiles.categories(profileId, year, input);
  }

  @GetMapping("/{profileId}/years/{year}/excel")
  public ResponseEntity<byte[]> download(@PathVariable String profileId, @PathVariable int year) {
    var result = profiles.download(profileId, year);
    return ResponseEntity.ok()
        .contentType(
            MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(result.fileName(), StandardCharsets.UTF_8)
                .build()
                .toString())
        .cacheControl(CacheControl.noStore())
        .body(result.bytes());
  }
}
