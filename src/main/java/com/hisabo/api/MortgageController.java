package com.hisabo.api;

import com.hisabo.mortgage.Mortgage.*;
import com.hisabo.mortgage.MortgageService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/profiles/{profileId}/mortgage")
@Validated
public class MortgageController {
  private final MortgageService service;

  public MortgageController(MortgageService service) {
    this.service = service;
  }

  @GetMapping
  public View read(@PathVariable String profileId) {
    return service.read(profileId);
  }

  @PostMapping(value = "/preview", consumes = "application/json")
  public Analysis preview(@PathVariable String profileId, @Valid @RequestBody PreviewInput input) {
    return service.preview(profileId, input);
  }

  @PutMapping(consumes = "application/json")
  public View save(@PathVariable String profileId, @Valid @RequestBody PlanInput input) {
    return service.save(profileId, input);
  }

  @PutMapping(value = "/home-sale", consumes = "application/json")
  public View homeSale(@PathVariable String profileId, @Valid @RequestBody HomeSaleInput input) {
    return service.saveHomeSale(profileId, input);
  }

  @PostMapping(value = "/improvements", consumes = "application/json")
  public View add(@PathVariable String profileId, @Valid @RequestBody ImprovementInput input) {
    return service.addImprovement(profileId, input);
  }

  @PutMapping(value = "/improvements/{id}", consumes = "application/json")
  public View edit(
      @PathVariable String profileId,
      @PathVariable UUID id,
      @Valid @RequestBody ImprovementInput input) {
    return service.saveImprovement(profileId, id, input);
  }

  @DeleteMapping("/improvements/{id}")
  public View delete(
      @PathVariable String profileId, @PathVariable UUID id, @RequestParam @Min(0) long revision) {
    return service.deleteImprovement(profileId, id, revision);
  }

  @GetMapping("/excel")
  public ResponseEntity<byte[]> excel(@PathVariable String profileId) {
    var result = service.download(profileId);
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
