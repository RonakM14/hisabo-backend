package com.hisabo;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "ledger.seed-demo=false")
class LedgerApiTest {
  static final Path directory;

  static {
    try {
      directory = Files.createTempDirectory("ledger-api-test-");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("ledger.data-dir", directory::toString);
  }

  @LocalServerPort int port;

  HttpResponse<String> request(String method, String path, String json) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api" + path))
            .header("Content-Type", "application/json")
            .method(
                method,
                json == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json))
            .build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void apiPersistsValidInputRejectsStaleAndInvalidInput() throws Exception {
    String valid =
        """
        {"revision":0,"type":"EXPENSE","description":"API lunch","amount":14.25,"category":"FOOD","account":"CHECKING","date":"2027-03-12"}
        """;
    assertEquals(200, request("POST", "/years/2027/transactions", valid).statusCode());
    assertEquals(409, request("POST", "/years/2027/transactions", valid).statusCode());
    assertEquals(
        400,
        request("POST", "/years/2027/transactions", valid.replace("14.25", "-1")).statusCode());
    assertEquals(
        400,
        request("POST", "/years/2027/transactions", valid.replace("14.25", "0.001")).statusCode());
    assertEquals(
        400,
        request("POST", "/years/2027/transactions", valid.replace("2027-03-12", "2027-02-30"))
            .statusCode());
    var read = request("GET", "/years/2027", null);
    assertEquals(200, read.statusCode());
    assertTrue(read.body().contains("API lunch"));
    assertTrue(Files.readString(directory.resolve("ledger-2027.xml")).contains("amount=\"14.25\""));
  }

  @Test
  void allBudgetCategoriesAreRequiredAndPersist() throws Exception {
    String valid =
        """
        {"revision":0,"budgets":{"HOUSING":1500,"FOOD":400,"TRANSPORT":200,"SHOPPING":300,"LIFESTYLE":250,"UTILITIES":100}}
        """;
    assertEquals(
        400,
        request(
                "PUT",
                "/years/2028/months/9/budgets",
                valid.replace("\"UTILITIES\":100", "\"UTILITIES\":-1"))
            .statusCode());
    assertEquals(200, request("PUT", "/years/2028/months/9/budgets", valid).statusCode());
    assertEquals(409, request("PUT", "/years/2028/months/9/budgets", valid).statusCode());
    assertEquals(
        400,
        request(
                "PUT",
                "/years/2028/months/13/budgets",
                valid.replace("\"revision\":0", "\"revision\":1"))
            .statusCode());
  }

  @Test
  void namedProfileApiScopesWritesAndDownloadsExcel() throws Exception {
    assertEquals(400, request("POST", "/profiles", "{\"name\":\" \"}").statusCode());
    var created = request("POST", "/profiles", "{\"name\":\"API Profile\"}");
    assertEquals(200, created.statusCode());
    var matcher = java.util.regex.Pattern.compile("\"id\":\"([^\"]+)\"").matcher(created.body());
    assertTrue(matcher.find());
    String profile = "/profiles/" + matcher.group(1);
    String valid =
        """
        {"revision":0,"type":"EXPENSE","description":"Only this profile","amount":22.50,"category":"FOOD","account":"CHECKING","date":"2031-04-12"}
        """;
    assertEquals(200, request("POST", profile + "/years/2031/transactions", valid).statusCode());
    assertFalse(
        request("GET", "/profiles/default/years/2031", null).body().contains("Only this profile"));
    assertEquals(409, request("POST", profile + "/years/2031/transactions", valid).statusCode());
    assertEquals(404, request("GET", "/profiles/unknown/years/2031", null).statusCode());
    assertEquals(400, request("GET", profile + "/years/1999", null).statusCode());
    assertEquals(200, request("PUT", profile, "{\"name\":\"Renamed Profile\"}").statusCode());
    var download =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        URI.create(
                            "http://127.0.0.1:" + port + "/api" + profile + "/years/2031/excel"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofByteArray());
    assertEquals(200, download.statusCode());
    assertTrue(
        download.headers().firstValue("Content-Type").orElseThrow().contains("spreadsheetml"));
    assertTrue(
        download.headers().firstValue("Content-Disposition").orElseThrow().contains("Renamed"));
    try (var book =
        new org.apache.poi.xssf.usermodel.XSSFWorkbook(
            new java.io.ByteArrayInputStream(download.body()))) {
      assertEquals(13, book.getNumberOfSheets());
      assertEquals(22.5, book.getSheet("April").getRow(11).getCell(1).getNumericCellValue());
    }
  }

  @Test
  void budgetApiReturnsSavingsAndCopiesDecemberIntoJanuary() throws Exception {
    var mapper = new tools.jackson.databind.ObjectMapper();
    var created = request("POST", "/profiles", "{\"name\":\"Carry API\"}");
    String path = "/profiles/" + mapper.readTree(created.body()).get("id").asString();
    String income =
        "{\"revision\":0,\"type\":\"INCOME\",\"description\":\"Salary\",\"amount\":1000.10,\"category\":\"SALARY\",\"account\":\"CHECKING\",\"date\":\"2035-12-01\"}";
    assertEquals(200, request("POST", path + "/years/2035/transactions", income).statusCode());
    String budget =
        "{\"revision\":1,\"budgets\":{\"HOUSING\":0,\"FOOD\":600.05,\"TRANSPORT\":0,\"SHOPPING\":0,\"LIFESTYLE\":0,\"UTILITIES\":0}}";
    var saved = request("PUT", path + "/years/2035/months/12/budgets", budget);
    assertEquals(200, saved.statusCode(), saved.body());
    var december = mapper.readTree(saved.body()).get("months").get(11);
    assertEquals(400.05, december.get("budgetSummary").get("savings").asDouble(), 0.001);
    var next = request("GET", path + "/years/2036", null);
    assertEquals(200, next.statusCode(), next.body());
    var january = mapper.readTree(next.body()).get("months").get(0);
    assertEquals("CARRIED", january.get("budgetOrigin").asString());
    assertEquals("2035-12", january.get("budgetSource").asString());
    assertEquals(600.05, january.get("budgets").get("FOOD").asDouble(), 0.001);
    assertEquals(0, january.get("transactions").size());
  }

  @Test
  void categoryApiValidatesAndScopesCustomTransactions() throws Exception {
    var mapper = new tools.jackson.databind.ObjectMapper();
    String created = request("POST", "/profiles", "{\"name\":\"Categories API\"}").body();
    String id = mapper.readTree(created).get("id").asString();
    String path = "/profiles/" + id;
    var entries =
        new java.util.ArrayList<>(com.hisabo.profile.BudgetCategories.defaults().entries());
    String custom = "CUSTOM_" + java.util.UUID.randomUUID();
    entries.add(
        new com.hisabo.profile.BudgetCategories.Entry(custom, "Education", "#123abc", true));
    String body =
        mapper.writeValueAsString(new com.hisabo.profile.BudgetCategories.Input(0L, entries));
    var saved = request("PUT", path + "/budget-categories?year=2026", body);
    assertEquals(200, saved.statusCode(), saved.body());
    assertTrue(saved.body().contains("Education"));
    assertEquals(409, request("PUT", path + "/budget-categories?year=2026", body).statusCode());
    assertEquals(
        400,
        request("PUT", path + "/budget-categories?year=2026", body.replace("#123abc", "invalid"))
            .statusCode());
    assertEquals(
        404, request("PUT", "/profiles/unknown/budget-categories?year=2026", body).statusCode());
    String transaction =
        "{\"revision\":0,\"type\":\"EXPENSE\",\"description\":\"Tuition\",\"amount\":42,\"category\":\""
            + custom
            + "\",\"account\":\"CHECKING\",\"date\":\"2026-10-03\"}";
    assertEquals(200, request("POST", path + "/years/2026/transactions", transaction).statusCode());
    assertEquals(
        400,
        request("POST", "/profiles/default/years/2026/transactions", transaction).statusCode());
    assertTrue(request("GET", path + "/years/2026", null).body().contains("Tuition"));
  }

  @Test
  void mortgageTemplateApiValidatesAndPersistsPlanAndExpenses() throws Exception {
    var created =
        request("POST", "/profiles", "{\"name\":\"Mortgage API\",\"template\":\"MORTGAGE\"}");
    assertEquals(200, created.statusCode());
    assertTrue(created.body().contains("MORTGAGE"));
    var matcher = java.util.regex.Pattern.compile("\"id\":\"([^\"]+)\"").matcher(created.body());
    assertTrue(matcher.find());
    String path = "/profiles/" + matcher.group(1) + "/mortgage";
    assertTrue(request("GET", path, null).body().contains("\"loan\":null"));
    String loan =
        """
        {"loan":{"startDate":"2020-01-31","balanceDate":"2026-01-31","balance":1000,"annualRate":12,"interestConvention":"MONTHLY","paymentMode":"PAYMENT","monthlyPayment":500,"remainingMonths":null,"monthlyEscrow":0},"scenario":{"monthlyExtra":100,"extraStartMonth":"2026-02","oneTimeExtra":0,"oneTimeMonth":null}}
        """;
    assertEquals(200, request("POST", path + "/preview", loan).statusCode());
    assertTrue(request("GET", path, null).body().contains("\"loan\":null"));
    String save = "{\"revision\":0," + loan.strip().substring(1);
    assertEquals(
        400, request("PUT", path, save.replace("\"balance\":1000", "\"balance\":-1")).statusCode());
    assertEquals(400, request("PUT", path, save.replace("2026-01-31", "2026-02-30")).statusCode());
    var result = request("PUT", path, save);
    assertEquals(200, result.statusCode());
    assertTrue(result.body().contains("\"interestSaved\":1.15"));
    assertEquals(409, request("PUT", path, save).statusCode());
    assertEquals(
        200,
        request(
                "POST",
                path + "/improvements",
                "{\"revision\":1,\"date\":\"2026-05-12\",\"project\":\"Extension\",\"description\":\"Materials\",\"amount\":2500}")
            .statusCode());
    assertTrue(request("GET", path, null).body().contains("Materials"));
    assertEquals(200, request("GET", path + "/excel", null).statusCode());
    assertEquals(404, request("GET", "/profiles/missing/mortgage", null).statusCode());
  }

  @Test
  void datedMortgagePaymentsValidatePreviewPersistAndProtectRevisions() throws Exception {
    var created =
        request("POST", "/profiles", "{\"name\":\"Dated Mortgage API\",\"template\":\"MORTGAGE\"}");
    assertEquals(200, created.statusCode());
    var mapper = new tools.jackson.databind.ObjectMapper();
    String path = "/profiles/" + mapper.readTree(created.body()).get("id").asText() + "/mortgage";
    String input =
        """
        {"loan":{"startDate":"2020-01-31","balanceDate":"2026-01-31","balance":1000,"annualRate":12,"interestConvention":"MONTHLY","paymentMode":"PAYMENT","monthlyPayment":200,"monthlyEscrow":0},
         "scenario":{"monthlyExtra":0,"oneTimePayments":[
           {"id":"11111111-1111-1111-1111-111111111111","date":"2026-02-10","amount":100},
           {"id":"22222222-2222-2222-2222-222222222222","date":"2026-03-01","amount":200}]}}
        """;
    var preview = request("POST", path + "/preview", input);
    assertEquals(200, preview.statusCode(), preview.body());
    assertEquals(2, mapper.readTree(preview.body()).get("oneTimeImpact").get("payments").size());
    assertTrue(mapper.readTree(request("GET", path, null).body()).get("loan").isNull());
    String save = "{\"revision\":0," + input.strip().substring(1);
    for (String invalid :
        new String[] {
          save.replace("2026-02-10", "2026-01-31"),
          save.replace("2026-02-10", "2026-02-30"),
          save.replace("\"amount\":100", "\"amount\":0"),
          save.replace("\"date\":\"2026-02-10\"", "\"date\":null"),
          save.replace(
              "22222222-2222-2222-2222-222222222222", "11111111-1111-1111-1111-111111111111")
        }) {
      assertEquals(400, request("PUT", path, invalid).statusCode());
    }
    var saved = request("PUT", path, save);
    assertEquals(200, saved.statusCode(), saved.body());
    assertEquals(409, request("PUT", path, save).statusCode());
    var result = mapper.readTree(request("GET", path, null).body());
    assertTrue(result.get("supportsDatedPayments").asBoolean());
    assertEquals(2, result.get("scenario").get("oneTimePayments").size());
    assertEquals(
        "2026-02-28",
        result
            .get("analysis")
            .get("oneTimeImpact")
            .get("payments")
            .get(0)
            .get("applicationDate")
            .asText());
    assertEquals(300, result.get("analysis").get("oneTimeImpact").get("applied").asInt());
    assertEquals(200, request("GET", path + "/excel", null).statusCode());
  }

  @Test
  void monthlyEscrowApiValidatesPersistsAndPreservesOmittedSchedules() throws Exception {
    var mapper = new tools.jackson.databind.ObjectMapper();
    var created =
        request("POST", "/profiles", "{\"name\":\"Monthly Escrow API\",\"template\":\"MORTGAGE\"}");
    assertEquals(200, created.statusCode());
    String path = "/profiles/" + mapper.readTree(created.body()).get("id").asText() + "/mortgage";
    String body =
        """
        {"revision":0,"loan":{"startDate":"2020-01-31","balanceDate":"2026-10-31","balance":1200,"annualRate":0,"interestConvention":"MONTHLY","paymentMode":"PAYMENT","monthlyPayment":100,"monthlyEscrow":50},"scenario":{"monthlyExtra":0,"escrowChanges":[{"month":"2026-12","amount":75.25,"scope":"FROM_MONTH"}]}}
        """;
    for (String invalid :
        new String[] {
          body.replace("2026-12", "2026-13"),
          body.replace("75.25", "-1"),
          body.replace("75.25", "0.001"),
          body.replace("FROM_MONTH", "INVALID"),
          body.replace("75.25", "null")
        }) assertEquals(400, request("PUT", path, invalid).statusCode());
    var result = request("PUT", path, body);
    assertEquals(200, result.statusCode(), result.body());
    var json = mapper.readTree(result.body());
    assertTrue(json.get("supportsMonthlyEscrow").asBoolean());
    assertEquals(
        900, json.get("analysis").get("housing").get("months").get(2).get("balance").asDouble());
    assertEquals(
        75.25, json.get("analysis").get("housing").get("months").get(2).get("escrow").asDouble());
    assertEquals(
        175.25,
        json.get("analysis").get("housing").get("months").get(2).get("totalPayment").asDouble());
    assertEquals(409, request("PUT", path, body).statusCode());
    String oldClient =
        body.replace("\"revision\":0", "\"revision\":1")
            .replace(
                ",\"escrowChanges\":[{\"month\":\"2026-12\",\"amount\":75.25,\"scope\":\"FROM_MONTH\"}]",
                "");
    var retained = request("PUT", path, oldClient);
    assertEquals(200, retained.statusCode(), retained.body());
    assertEquals(1, mapper.readTree(retained.body()).get("scenario").get("escrowChanges").size());
    assertEquals(
        1,
        mapper
            .readTree(request("GET", path, null).body())
            .get("scenario")
            .get("escrowChanges")
            .size());
  }

  @Test
  void homeSaleApiValidatesSavesAndProtectsRevisions() throws Exception {
    var mapper = new tools.jackson.databind.ObjectMapper();
    var created = request("POST", "/profiles", "{\"name\":\"Sale API\",\"template\":\"MORTGAGE\"}");
    String path = "/profiles/" + mapper.readTree(created.body()).get("id").asString() + "/mortgage";
    String sale =
        """
        {"revision":0,"homeSale":{"salePrice":500000,"closingDate":"2026-12-01","payoffOverride":300000,"agentFeePercent":5,"closingCosts":5000,"buyerCredits":0,"otherLoans":0,"taxEnabled":true,"taxReserve":15000}}
        """;
    assertEquals(400, request("PUT", path + "/home-sale", sale).statusCode());
    String plan =
        """
        {"revision":0,"loan":{"startDate":"2020-01-01","balanceDate":"2026-10-01","balance":300000,"annualRate":5,"interestConvention":"MONTHLY","paymentMode":"PAYMENT","monthlyPayment":2000,"monthlyEscrow":0},"scenario":{"monthlyExtra":0}}
        """;
    assertEquals(200, request("PUT", path, plan).statusCode());
    sale = sale.replace("\"revision\":0", "\"revision\":1");
    for (String invalid :
        new String[] {
          sale.replace("500000", "0"),
          sale.replace("2026-12-01", "2026-09-01"),
          sale.replace("2026-12-01", "2026-02-30"),
          sale.replace("\"agentFeePercent\":5", "\"agentFeePercent\":101"),
          sale.replace("5000,", "0.001,"),
          sale.replace("\"taxReserve\":15000", "\"taxReserve\":null")
        }) assertEquals(400, request("PUT", path + "/home-sale", invalid).statusCode());
    var saved = request("PUT", path + "/home-sale", sale);
    assertEquals(200, saved.statusCode(), saved.body());
    var result = mapper.readTree(saved.body());
    assertEquals(155000, result.get("homeSaleEstimate").get("cashInHand").asDouble());
    assertTrue(result.get("supportsHomeSale").asBoolean());
    assertEquals(409, request("PUT", path + "/home-sale", sale).statusCode());
    assertEquals(
        500000,
        mapper
            .readTree(request("GET", path, null).body())
            .get("homeSale")
            .get("salePrice")
            .asDouble());
    assertEquals(404, request("PUT", "/profiles/missing/mortgage/home-sale", sale).statusCode());
  }

  @Test
  void browserProxyOriginsCanWriteAndUnknownOriginsAreRejected() throws Exception {
    for (var origin :
        Map.of(
                "http://127.0.0.1:5173", 200,
                "http://localhost:5173", 200,
                "http://hisabo.localhost:5173", 200,
                "http://hisabo.localhost:4173", 200,
                "https://untrusted.example", 403,
                "http://other.localhost:5173", 403)
            .entrySet()) {
      var request =
          HttpRequest.newBuilder(
                  URI.create("http://127.0.0.1:" + port + "/api/years/2029/transactions"))
              .header("Origin", origin.getKey())
              .header("Access-Control-Request-Method", "POST")
              .header("Access-Control-Request-Headers", "content-type")
              .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
              .build();
      var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
      assertEquals(origin.getValue(), response.statusCode(), origin.getKey());
      if (origin.getValue() == 200) {
        assertEquals(
            origin.getKey(),
            response.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
      }
    }
  }
}
