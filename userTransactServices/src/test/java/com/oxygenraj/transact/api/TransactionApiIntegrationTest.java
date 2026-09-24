package com.oxygenraj.transact.api;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "eureka.client.enabled=false", "user-transact.database-port.enabled=false",
    "user-transact.eureka.database-port.enabled=false", "user-transact.database-check.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:transactions;MODE=Oracle;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.sql.init.mode=always", "spring.sql.init.schema-locations=classpath:test-schema.sql",
    "server.address=127.0.0.1"
})
class TransactionApiIntegrationTest {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    @BeforeEach void clearData() { jdbc.update("DELETE FROM USER_TRANSACT_SCHEMA.\"TRANSACTION\""); }
    @AfterAll static void closeHttp() { HTTP.shutdownNow(); }

    @Test void createPersistsOnlyTransactionFieldsAndReturnsLocation() throws Exception {
        var payload = input();
        payload.put("monthName", " september ");
        var response = call("POST", "/transactions", payload);
        assertThat(response.statusCode()).isEqualTo(201);
        var body = json(response);
        assertThat(body.get("id").longValue()).isPositive();
        assertThat(body.get("userId").longValue()).isEqualTo(42);
        assertThat(body.get("monthName").stringValue()).isEqualTo("SEPTEMBER");
        assertThat(body.get("monthCount").intValue()).isEqualTo(24);
        assertThat(body.get("amount").decimalValue()).isEqualByComparingTo("1234.56");
        assertThat(body.get("version").longValue()).isZero();
        assertThat(body.get("createdAt").stringValue()).isNotBlank();
        assertThat(body.get("modifiedAt").stringValue()).isNotBlank();
        assertThat(response.headers().firstValue("Location").orElseThrow()).endsWith("/transactions/" + body.get("id").longValue());
        assertThat(response.body()).doesNotContain("password", "username", "email");
        assertThat(jdbc.queryForObject("SELECT AMOUNT FROM USER_TRANSACT_SCHEMA.\"TRANSACTION\"", BigDecimal.class))
                .isEqualByComparingTo("1234.56");
    }

    @Test void getByIdIsPublicAndDoesNotRequireUserSchemaToExist() throws Exception {
        long id = create(input());
        var response = call("GET", "/transactions/" + id, null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).get("userId").longValue()).isEqualTo(42);
    }

    @Test void emptyListHasPaginationMetadata() throws Exception {
        var response = call("GET", "/transactions", null);
        assertThat(response.statusCode()).isEqualTo(200);
        var body = json(response);
        assertThat(body.get("items").size()).isZero();
        assertThat(body.get("page").intValue()).isZero();
        assertThat(body.get("size").intValue()).isEqualTo(20);
        assertThat(body.get("totalElements").longValue()).isZero();
        assertThat(body.get("totalPages").longValue()).isZero();
    }

    @Test void listFiltersByUserAndPaginatesInStableIdOrder() throws Exception {
        long first = create(input());
        var differentUser = input(); differentUser.put("userId", 77);
        create(differentUser);
        long last = create(input());
        var page0 = json(call("GET", "/transactions?userId=42&page=0&size=1", null));
        var page1 = json(call("GET", "/transactions?userId=42&page=1&size=1", null));
        assertThat(page0.get("items").size()).isEqualTo(1);
        assertThat(page0.get("items").get(0).get("id").longValue()).isEqualTo(first);
        assertThat(page1.get("items").get(0).get("id").longValue()).isEqualTo(last);
        assertThat(page0.get("totalElements").longValue()).isEqualTo(2);
        assertThat(page0.get("totalPages").longValue()).isEqualTo(2);
        assertThat(json(call("GET", "/transactions?page=2&size=20", null)).get("items").size()).isZero();
        assertThat(json(call("GET", "/transactions", null)).get("totalElements").longValue()).isEqualTo(3);
    }

    @Test void updatePreservesIdentityAndCreationTimeAndIncrementsVersion() throws Exception {
        long id = create(input());
        String createdAt = json(call("GET", "/transactions/" + id, null)).get("createdAt").stringValue();
        var update = input(); update.put("version", 0); update.put("amount", new BigDecimal("987.65"));
        update.put("monthCount", 36); update.put("monthName", "October"); update.put("userId", 77);
        var response = call("PUT", "/transactions/" + id, update);
        assertThat(response.statusCode()).isEqualTo(200);
        var body = json(response);
        assertThat(body.get("id").longValue()).isEqualTo(id);
        assertThat(body.get("version").longValue()).isEqualTo(1);
        assertThat(body.get("amount").decimalValue()).isEqualByComparingTo("987.65");
        assertThat(body.get("monthName").stringValue()).isEqualTo("OCTOBER");
        assertThat(body.get("monthCount").intValue()).isEqualTo(36);
        assertThat(body.get("userId").longValue()).isEqualTo(77);
        assertThat(body.get("createdAt").stringValue()).isEqualTo(createdAt);
        assertThat(json(call("GET", "/transactions/" + id, null)).get("version").longValue()).isEqualTo(1);
    }

    @Test void staleUpdateCannotOverwriteNewerAmount() throws Exception {
        long id = create(input());
        var update = input(); update.put("version", 0); update.put("amount", new BigDecimal("15.25"));
        assertThat(call("PUT", "/transactions/" + id, update).statusCode()).isEqualTo(200);
        update.put("amount", new BigDecimal("99.99"));
        var stale = call("PUT", "/transactions/" + id, update);
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(json(stale).get("code").stringValue()).isEqualTo("VERSION_CONFLICT");
        assertThat(json(call("GET", "/transactions/" + id, null)).get("amount").decimalValue()).isEqualByComparingTo("15.25");
    }

    @Test void simultaneousUpdatesHaveOnlyOneWinner() throws Exception {
        long id = create(input());
        var update = input(); update.put("version", 0);
        var request = request("PUT", "/transactions/" + id, mapper.writeValueAsString(update));
        var a = HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        var b = HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        CompletableFuture.allOf(a, b).get();
        assertThat(List.of(a.get().statusCode(), b.get().statusCode())).containsExactlyInAnyOrder(200, 409);
        assertThat(json(call("GET", "/transactions/" + id, null)).get("version").longValue()).isEqualTo(1);
    }

    @Test void identicalCreatesAreSeparateRecordsNotAnUndocumentedUpsert() throws Exception {
        assertThat(create(input())).isNotEqualTo(create(input()));
        assertThat(json(call("GET", "/transactions", null)).get("totalElements").longValue()).isEqualTo(2);
    }

    @ParameterizedTest @ValueSource(strings = {"JANUARY","FEBRUARY","MARCH","APRIL","MAY","JUNE","JULY","AUGUST","SEPTEMBER","OCTOBER","NOVEMBER","DECEMBER"})
    void allCalendarMonthNamesAccepted(String month) throws Exception {
        var body = input(); body.put("monthName", month);
        assertThat(call("POST", "/transactions", body).statusCode()).isEqualTo(201);
    }

    @ParameterizedTest @ValueSource(strings = {"0", "0.01", "99999999999999999.99"})
    void allowedAmountsRoundTripWithoutPrecisionLoss(String amount) throws Exception {
        var body = input(); body.put("amount", new BigDecimal(amount));
        long id = create(body);
        assertThat(jdbc.queryForObject("SELECT AMOUNT FROM USER_TRANSACT_SCHEMA.\"TRANSACTION\" WHERE ID=?", BigDecimal.class, id))
                .isEqualByComparingTo(amount);
    }

    @ParameterizedTest @MethodSource("invalidFields")
    void rejectsInvalidCreateWithoutWriting(String field, Object value) throws Exception {
        var body = input(); body.put(field, value);
        var response = call("POST", "/transactions", body);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(json(response).get("code").isString()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM USER_TRANSACT_SCHEMA.\"TRANSACTION\"", Long.class)).isZero();
    }

    static Stream<Arguments> invalidFields() {
        return Stream.of(
            Arguments.of("userId", null), Arguments.of("userId", 0), Arguments.of("userId", -1),
            Arguments.of("userId", new BigDecimal("1.5")), Arguments.of("userId", new BigDecimal("9223372036854775808")),
            Arguments.of("monthName", null), Arguments.of("monthName", ""), Arguments.of("monthName", "  "),
            Arguments.of("monthName", "13"), Arguments.of("monthName", "JAN"),
            Arguments.of("monthName", "JANUARY'; DROP TABLE TRANSACTION;--"),
            Arguments.of("monthCount", null), Arguments.of("monthCount", 0), Arguments.of("monthCount", -1),
            Arguments.of("monthCount", new BigDecimal("1.5")), Arguments.of("monthCount", 2147483648L),
            Arguments.of("amount", null), Arguments.of("amount", new BigDecimal("-0.01")),
            Arguments.of("amount", new BigDecimal("1.001")), Arguments.of("amount", new BigDecimal("100000000000000000.00")),
            Arguments.of("amount", "not-an-amount"));
    }

    @ParameterizedTest @ValueSource(strings = {"?page=-1","?size=0","?size=101","?userId=0","?userId=-1","?page=x","?userId=1.5","?page=2147483648"})
    void invalidPaginationOrFiltersAre400(String query) throws Exception {
        assertThat(call("GET", "/transactions" + query, null).statusCode()).isEqualTo(400);
    }

    @ParameterizedTest @ValueSource(strings = {"0","-1","no-id","1.5","9223372036854775808"})
    void invalidPathIdsAre400(String id) throws Exception {
        assertThat(call("GET", "/transactions/" + id, null).statusCode()).isEqualTo(400);
    }

    @Test void missingRecordIs404ForBothGetAndUpdate() throws Exception {
        assertThat(call("GET", "/transactions/9223372036854775807", null).statusCode()).isEqualTo(404);
        var update = input(); update.put("version", 0);
        assertThat(call("PUT", "/transactions/9223372036854775807", update).statusCode()).isEqualTo(404);
    }

    @Test void versionRequiredAndNonnegativeOnUpdate() throws Exception {
        long id = create(input());
        assertThat(call("PUT", "/transactions/" + id, input()).statusCode()).isEqualTo(400);
        var body = input(); body.put("version", -1);
        assertThat(call("PUT", "/transactions/" + id, body).statusCode()).isEqualTo(400);
        body.put("version", new BigDecimal("0.9"));
        assertThat(call("PUT", "/transactions/" + id, body).statusCode()).isEqualTo(400);
    }

    @Test void unknownFieldsAreRejectedRatherThanSilentlyStoringUserDetails() throws Exception {
        var body = input(); body.put("username", "must-not-store-user-details");
        assertThat(call("POST", "/transactions", body).statusCode()).isEqualTo(400);
    }

    @Test void malformedJsonAndUnsupportedDeleteDoNotChangeData() throws Exception {
        var response = HTTP.send(request("POST", "/transactions", "{not-json"), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(400);
        long id = create(input());
        assertThat(call("DELETE", "/transactions/" + id, null).statusCode()).isEqualTo(403);
        assertThat(call("GET", "/transactions/" + id, null).statusCode()).isEqualTo(200);
    }

    @Test void serviceDoesNotPublishUnrequestedActuatorDataAndResponsesAreNotCached() throws Exception {
        long id = create(input());
        var response = call("GET", "/transactions/" + id, null);
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(response.headers().firstValue("X-Content-Type-Options").orElseThrow()).isEqualTo("nosniff");
        assertThat(call("GET", "/actuator/env", null).statusCode()).isEqualTo(403);
    }

    @Test void databaseFailureEnvelopeDoesNotRevealSqlOrSecrets() {
        var response = new ApiExceptionHandler().unavailable(
                new org.springframework.jdbc.CannotGetJdbcConnectionException("sensitive-password SELECT * FROM private_table"));
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(mapper.writeValueAsString(response.getBody())).doesNotContain("sensitive-password", "SELECT", "private_table");
        assertThat(response.getBody().code()).isEqualTo("DATABASE_UNAVAILABLE");
    }

    private LinkedHashMap<String, Object> input() {
        var body = new LinkedHashMap<String, Object>();
        body.put("userId", 42L); body.put("monthName", "SEPTEMBER");
        body.put("monthCount", 24); body.put("amount", new BigDecimal("1234.56"));
        return body;
    }
    private long create(Map<String, Object> body) throws Exception {
        var response = call("POST", "/transactions", body);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return json(response).get("id").longValue();
    }
    private HttpResponse<String> call(String method, String path, Object body) throws Exception {
        return HTTP.send(request(method, path, body == null ? null : mapper.writeValueAsString(body)), HttpResponse.BodyHandlers.ofString());
    }
    private HttpRequest request(String method, String path, String body) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/userTransactServices" + path))
                .timeout(Duration.ofSeconds(10)).header("Accept", "application/json").header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
    }
    private JsonNode json(HttpResponse<String> response) { return mapper.readTree(response.body()); }
}
