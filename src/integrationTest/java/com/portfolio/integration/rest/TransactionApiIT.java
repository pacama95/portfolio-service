package com.portfolio.integration.rest;

import com.github.database.rider.core.api.configuration.DBUnit;
import com.github.database.rider.core.api.connection.ConnectionHolder;
import com.github.database.rider.core.api.dataset.DataSet;
import com.github.database.rider.junit5.api.DBRider;
import com.portfolio.integration.IntegrationTestProfile;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.enterprise.inject.spi.CDI;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@DBRider
@DBUnit(schema = "public", caseSensitiveTableNames = true, cacheConnection = false)
class TransactionApiIT {

    private static final String USER_A = "user-a";
    private static final String USER_B = "user-b";
    private static final String AAPL_BUY_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

    @SuppressWarnings("unused")
    private final ConnectionHolder connectionHolder =
            () -> CDI.current().select(AgroalDataSource.class).get().getConnection();

    @Test
    void givenMissingUserHeader_whenCreate_thenBadRequest() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "ticker": "AAPL",
                          "transactionType": "BUY",
                          "assetType": "COMMON_STOCK",
                          "quantity": 10,
                          "price": 100,
                          "currency": "USD",
                          "transactionDate": "2024-01-01"
                        }
                        """)
                .when()
                .post("/api/transactions")
                .then()
                .statusCode(400);
    }

    @Test
    @DataSet(cleanBefore = true)
    void givenValidRequest_whenCreateAndGet_thenScopedToUser() {
        String id = given()
                .header("X-User-Id", USER_A)
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "ticker": "AAPL",
                          "transactionType": "BUY",
                          "assetType": "COMMON_STOCK",
                          "quantity": 10,
                          "price": 100,
                          "fees": 1,
                          "currency": "USD",
                          "transactionDate": "2024-01-01",
                          "exchange": "NASDAQ",
                          "country": "US",
                          "companyName": "Apple"
                        }
                        """)
                .when()
                .post("/api/transactions")
                .then()
                .statusCode(201)
                .body("id", notNullValue())
                .body("ticker", equalTo("AAPL"))
                .extract()
                .path("id");

        given()
                .header("X-User-Id", USER_A)
                .when()
                .get("/api/transactions/" + id)
                .then()
                .statusCode(200)
                .body("ticker", equalTo("AAPL"));

        given()
                .header("X-User-Id", USER_B)
                .when()
                .get("/api/transactions/" + id)
                .then()
                .statusCode(404);

        given()
                .header("X-User-Id", USER_B)
                .when()
                .get("/api/transactions")
                .then()
                .statusCode(200)
                .body("$", hasSize(0));
    }

    @Test
    @DataSet(cleanBefore = true)
    void givenUserATransaction_whenUserBDeletes_thenNotFound() {
        String id = given()
                .header("X-User-Id", USER_A)
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "ticker": "MSFT",
                          "transactionType": "BUY",
                          "assetType": "COMMON_STOCK",
                          "quantity": 5,
                          "price": 200,
                          "currency": "USD",
                          "transactionDate": "2024-02-01"
                        }
                        """)
                .when()
                .post("/api/transactions")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        given()
                .header("X-User-Id", USER_B)
                .when()
                .delete("/api/transactions/" + id)
                .then()
                .statusCode(404);

        given()
                .header("X-User-Id", USER_A)
                .when()
                .delete("/api/transactions/" + id)
                .then()
                .statusCode(204);
    }

    @Test
    @DataSet(cleanBefore = true, executeScriptsBefore = "datasets/transactions-seed.sql")
    void givenSeededTransactions_whenListAll_thenReturnsUserScopedRows() {
        given()
                .header("X-User-Id", USER_A)
                .when()
                .get("/api/transactions")
                .then()
                .statusCode(200)
                .body("$", hasSize(3));

        given()
                .header("X-User-Id", USER_B)
                .when()
                .get("/api/transactions")
                .then()
                .statusCode(200)
                .body("$", hasSize(1))
                .body("[0].ticker", equalTo("GOOG"));
    }

    @Test
    @DataSet(cleanBefore = true, executeScriptsBefore = "datasets/transactions-seed.sql")
    void givenSeededTransactions_whenGetById_thenReturnsRowOrNotFound() {
        given()
                .header("X-User-Id", USER_A)
                .when()
                .get("/api/transactions/" + AAPL_BUY_ID)
                .then()
                .statusCode(200)
                .body("ticker", equalTo("AAPL"))
                .body("transactionType", equalTo("BUY"));

        given()
                .header("X-User-Id", USER_B)
                .when()
                .get("/api/transactions/" + AAPL_BUY_ID)
                .then()
                .statusCode(404);
    }

    @Test
    @DataSet(cleanBefore = true, executeScriptsBefore = "datasets/transactions-seed.sql")
    void givenSeededTransactions_whenListByTicker_thenFiltersToTicker() {
        given()
                .header("X-User-Id", USER_A)
                .when()
                .get("/api/transactions/ticker/AAPL")
                .then()
                .statusCode(200)
                .body("$", hasSize(2))
                .body("ticker", everyItem(equalTo("AAPL")));
    }

    @Test
    @DataSet(cleanBefore = true, executeScriptsBefore = "datasets/transactions-seed.sql")
    void givenSeededTransactions_whenSearchWithFilters_thenReturnsMatchingRows() {
        given()
                .header("X-User-Id", USER_A)
                .queryParam("ticker", "AAPL")
                .queryParam("type", "BUY")
                .queryParam("fromDate", "2024-01-01")
                .queryParam("toDate", "2024-01-03")
                .queryParam("sort", "ASC")
                .when()
                .get("/api/transactions/search")
                .then()
                .statusCode(200)
                .body("$", hasSize(1))
                .body("[0].id", equalTo(AAPL_BUY_ID))
                .body("[0].transactionType", equalTo("BUY"));
    }

    @Test
    @DataSet(cleanBefore = true, executeScriptsBefore = "datasets/transactions-seed.sql")
    void givenSeededTransaction_whenUpdate_thenPersistsChanges() {
        given()
                .header("X-User-Id", USER_A)
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "quantity": 15,
                          "price": 105,
                          "companyName": "Apple Inc"
                        }
                        """)
                .when()
                .put("/api/transactions/" + AAPL_BUY_ID)
                .then()
                .statusCode(200)
                .body("quantity", anyOf(equalTo(15), equalTo(15.0f), equalTo(15.0), equalTo(15.000000f)))
                .body("price", anyOf(equalTo(105), equalTo(105.0f), equalTo(105.0), equalTo(105.000000f)))
                .body("companyName", equalTo("Apple Inc"));
    }

    @Test
    @DataSet(cleanBefore = true, executeScriptsBefore = "datasets/transactions-seed.sql")
    void givenOtherUsersTransaction_whenUpdate_thenNotFound() {
        given()
                .header("X-User-Id", USER_B)
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "quantity": 15
                        }
                        """)
                .when()
                .put("/api/transactions/" + AAPL_BUY_ID)
                .then()
                .statusCode(404);
    }

    @Test
    @DataSet(cleanBefore = true, executeScriptsBefore = "datasets/transactions-seed.sql")
    void givenSeededTransactions_whenCount_thenReturnsUserScopedCounts() {
        given()
                .header("X-User-Id", USER_A)
                .when()
                .get("/api/transactions/count")
                .then()
                .statusCode(200)
                .body(equalTo("3"));

        given()
                .header("X-User-Id", USER_A)
                .when()
                .get("/api/transactions/count/AAPL")
                .then()
                .statusCode(200)
                .body(equalTo("2"));

        given()
                .header("X-User-Id", USER_B)
                .when()
                .get("/api/transactions/count")
                .then()
                .statusCode(200)
                .body(equalTo("1"));
    }

    @Test
    @DataSet(cleanBefore = true, executeScriptsBefore = "datasets/transactions-seed.sql")
    void givenSeededTransaction_whenDelete_thenRemovedForOwnerOnly() {
        given()
                .header("X-User-Id", USER_A)
                .when()
                .delete("/api/transactions/" + AAPL_BUY_ID)
                .then()
                .statusCode(204);

        given()
                .header("X-User-Id", USER_A)
                .when()
                .get("/api/transactions/" + AAPL_BUY_ID)
                .then()
                .statusCode(404);

        given()
                .header("X-User-Id", USER_A)
                .when()
                .get("/api/transactions/count")
                .then()
                .statusCode(200)
                .body(equalTo("2"));
    }

    @Test
    @DataSet(cleanBefore = true)
    void givenNoTransactions_whenList_thenEmpty() {
        given()
                .header("X-User-Id", USER_A)
                .when()
                .get("/api/transactions")
                .then()
                .statusCode(200)
                .body("$", hasSize(0));
    }
}
