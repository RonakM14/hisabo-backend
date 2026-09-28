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
