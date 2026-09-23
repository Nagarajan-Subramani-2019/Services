package com.oxygenraj.userinfo.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises the real HTTP/security/JDBC stack against a disposable Oracle-mode database. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:userinfo;MODE=Oracle;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=test-only",
        "user-info.properties-datasource.url=jdbc:oracle:thin:@//properties.example.test:1/FREEPDB1",
        "user-info.properties-datasource.username=EUREKA_DB",
        "user-info.properties-datasource.password=separate-properties-test-only",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:test-schema.sql",
        "user-info.database-port.enabled=false",
        "user-info.database-check.enabled=false",
        "eureka.client.enabled=false",
        "server.port=0",
        "server.address=127.0.0.1"
})
class UserApiIntegrationTest {
    private static final String PASSWORD = "StrongSecret9!";
    private static final String PHONE = "+919876543210";
    private static final String TABLE = "USER_INFO_SCHEMA.USER_DETAILS";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private HttpClient client;

    @BeforeEach
    void resetDisposableDatabase() {
        jdbc.update("DELETE FROM " + TABLE);
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    void closeClient() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void crudPoolDoesNotUseTheSeparatePropertiesDatabaseSettings() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:userinfo");
            assertThat(connection.getMetaData().getUserName()).isEqualToIgnoringCase("sa");
        }
    }

    @Test
    void publicRegistrationNormalizesIdentityAndStoresOnlyCost12Bcrypt() throws Exception {
        HttpResponse<String> response = post("/userdetails", registration("Alice_1", "ALICE@EXAMPLE.COM"));

        assertThat(response.statusCode()).isEqualTo(201);
        Map<String, Object> user = body(response);
        assertUser(user, "alice_1", "alice@example.com", "USER");
        long id = id(user);
        assertThat(response.headers().firstValue("Location")).contains(baseUri() + "/userdetails/" + id);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        assertNoSecrets(response, PASSWORD);

        Map<String, Object> stored = jdbc.queryForMap("SELECT * FROM " + TABLE + " WHERE ID = ?", id);
        String hash = (String) stored.get("PASSWORD_HASH");
        assertThat(hash).matches("\\$2[aby]\\$12\\$.{53}").isNotEqualTo(PASSWORD);
        assertThat(passwordEncoder.matches(PASSWORD, hash)).isTrue();
        assertThat(stored.get("USERNAME")).isEqualTo("alice_1");
        assertThat(stored.get("EMAIL")).isEqualTo("alice@example.com");
        assertThat(stored.get("USER_ROLE")).isEqualTo("USER");
        assertThat(((Number) stored.get("ENABLED")).intValue()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"username", "email"})
    void caseInsensitiveDuplicatesReturnConflictWithoutDatabaseDetails(String duplicateField) throws Exception {
        create("existing", "existing@example.com");
        Map<String, Object> request = registration("newuser", "new@example.com");
        request.put(duplicateField, duplicateField.equals("username") ? "EXISTING" : "EXISTING@EXAMPLE.COM");

        HttpResponse<String> response = post("/userdetails", request);

        assertSafeError(response, 409, PASSWORD);
        assertThat(response.body()).doesNotContain("INSERT INTO", "JdbcSQL", "PASSWORD_HASH", "USER_INFO_SCHEMA");
        assertThat(countUsers()).isEqualTo(1);
    }

    @ParameterizedTest(name = "invalid {0}: {1}")
    @MethodSource("invalidRegistrationFields")
    void rejectsInvalidRegistrationWithoutPersistingOrEchoingSecrets(String field, String description, Object value)
            throws Exception {
        Map<String, Object> request = registration("validuser", "valid@example.com");
        request.put(field, value);

        HttpResponse<String> response = post("/userdetails", request);

        assertSafeError(response, 400, PASSWORD);
        if (field.equals("password") && value instanceof String secret && !secret.isBlank()) {
            assertNoSecrets(response, secret);
        }
        assertThat(countUsers()).isZero();
    }

    static Stream<Arguments> invalidRegistrationFields() {
        return Stream.of(
                Arguments.of("username", "missing", null),
                Arguments.of("username", "blank", " "),
                Arguments.of("username", "too short", "ab"),
                Arguments.of("username", "too long", "a".repeat(65)),
                Arguments.of("username", "non-ASCII", "usér"),
                Arguments.of("username", "embedded whitespace", "some user"),
                Arguments.of("email", "missing", null),
                Arguments.of("email", "blank", ""),
                Arguments.of("email", "malformed", "not-an-email"),
                Arguments.of("email", "too long", "a".repeat(64) + "@" + "b".repeat(63) + "."
                        + "c".repeat(63) + "." + "d".repeat(62)),
                Arguments.of("phoneNumber", "missing", null),
                Arguments.of("phoneNumber", "blank", ""),
                Arguments.of("phoneNumber", "too short", "123456"),
                Arguments.of("phoneNumber", "too long", "1".repeat(21)),
                Arguments.of("phoneNumber", "letters", "123456a"),
                Arguments.of("phoneNumber", "embedded plus", "123+4567890"),
                Arguments.of("phoneNumber", "spaces", "+91 9876543210"),
                Arguments.of("password", "missing", null),
                Arguments.of("password", "blank", " ".repeat(10)),
                Arguments.of("password", "nine ASCII characters", "Secret9!a"),
                Arguments.of("password", "five accented characters", "é".repeat(5)),
                Arguments.of("password", "five Unicode code points", "😀".repeat(5)),
                Arguments.of("password", "73 ASCII bytes", "x".repeat(73)),
                Arguments.of("password", "74 UTF-8 bytes", "é".repeat(37)));
    }

    @ParameterizedTest
    @MethodSource("validPasswordBoundaries")
    void acceptsPasswordBoundariesAndAuthenticatesWithoutTruncation(String password) throws Exception {
        Map<String, Object> registration = registration("boundary", "boundary@example.com");
        registration.put("password", password);
        HttpResponse<String> created = post("/userdetails", registration);
        assertThat(created.statusCode()).isEqualTo(201);
        assertNoSecrets(created, password);

        HttpResponse<String> response = post("/authuserdetails", credentials("BOUNDARY", password));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body(response).get("authenticated")).isEqualTo(true);
        assertNoSecrets(response, password);
    }

    static Stream<String> validPasswordBoundaries() {
        return Stream.of("Secret123!", "x".repeat(72), "é".repeat(36));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1234567", "+1234567", "12345678901234567890", "+12345678901234567890"})
    void acceptsPhoneNumberDigitBoundaries(String phoneNumber) throws Exception {
        Map<String, Object> request = registration("phoneuser", "phone@example.com");
        request.put("phoneNumber", phoneNumber);

        HttpResponse<String> response = post("/userdetails", request);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(body(response).get("phoneNumber")).isEqualTo(phoneNumber);
    }

    @Test
    void malformedJsonDoesNotReflectItsPassword() throws Exception {
        String secret = "NeverReflectThisSecret9!";
        HttpResponse<String> response = send("POST", "/userdetails",
                "{\"username\":\"broken\",\"password\":\"" + secret + "\",", null);

        assertSafeError(response, 400, secret);
        assertThat(countUsers()).isZero();
    }

    @Test
    void publicCredentialCheckReturnsSafeUserButCreatesNoSessionOrToken() throws Exception {
        Map<String, Object> created = create("verifyuser", "verify@example.com");

        HttpResponse<String> response = post("/authuserdetails", credentials("VERIFYUSER", PASSWORD));

        assertThat(response.statusCode()).isEqualTo(200);
        Map<String, Object> result = body(response);
        assertThat(result).containsOnlyKeys("authenticated", "user").containsEntry("authenticated", true);
        Map<String, Object> user = object(result.get("user"));
        assertUser(user, "verifyuser", "verify@example.com", "USER");
        assertThat(id(user)).isEqualTo(id(created));
        assertNoSecrets(response, PASSWORD);
        assertThat(response.headers().allValues("Set-Cookie")).noneMatch(value -> value.contains("JSESSIONID"));
        assertSafeError(get("/actuator/info", null), 401, PASSWORD);
    }

    @Test
    void unknownWrongAndDisabledCredentialsShareGenericUnauthorizedResponse() throws Exception {
        create("knownuser", "known@example.com");
        Map<String, Object> disabled = create("disableduser", "disabled@example.com");
        jdbc.update("UPDATE " + TABLE + " SET ENABLED = 0 WHERE ID = ?", id(disabled));

        HttpResponse<String> wrong = post("/authuserdetails", credentials("knownuser", "WrongSecret9!"));
        HttpResponse<String> unknown = post("/authuserdetails", credentials("unknownuser", PASSWORD));
        HttpResponse<String> inactive = post("/authuserdetails", credentials("disableduser", PASSWORD));

        for (HttpResponse<String> response : List.of(wrong, unknown, inactive)) {
            assertSafeError(response, 401, PASSWORD, "WrongSecret9!");
            assertThat(response.body()).doesNotContain("knownuser", "unknownuser", "disableduser");
            assertThat(body(response).get("code")).isEqualTo(body(wrong).get("code"));
            assertThat(body(response).get("message")).isEqualTo(body(wrong).get("message"));
        }
    }

    @Test
    void anonymousReaderCanListAnEmptyTableWithoutAnAuthenticationChallenge() throws Exception {
        HttpResponse<String> response = get("/userdetails", null);

        assertThat(response.statusCode()).isEqualTo(200);
        Map<String, Object> page = body(response);
        assertThat((List<?>) page.get("content")).isEmpty();
        assertThat(((Number) page.get("page")).intValue()).isZero();
        assertThat(((Number) page.get("size")).intValue()).isEqualTo(20);
        assertThat(((Number) page.get("totalElements")).longValue()).isZero();
        assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
        assertNoSecrets(response, PASSWORD);
    }

    @Test
    void anonymousReaderCanReadAnyExistingUserAndOnlyMissingRecordsReturnNotFound() throws Exception {
        Map<String, Object> first = create("firstuser", "first@example.com");
        Map<String, Object> other = create("otheruser", "other@example.com");

        for (Map<String, Object> created : List.of(first, other)) {
            HttpResponse<String> response = get("/userdetails/" + id(created), null);
            assertThat(response.statusCode()).isEqualTo(200);
            assertUser(body(response), (String) created.get("username"), (String) created.get("email"), "USER");
            assertThat(id(body(response))).isEqualTo(id(created));
            assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
            assertNoSecrets(response, PASSWORD);
        }

        HttpResponse<String> missing = get("/userdetails/" + Long.MAX_VALUE, null);
        assertSafeError(missing, 404, PASSWORD);
        assertThat(body(missing).get("code")).isEqualTo("NOT_FOUND");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "not-a-number", "9223372036854775808"})
    void anonymousIndividualReadStillValidatesTheRequestedId(String invalidId) throws Exception {
        assertSafeError(get("/userdetails/" + invalidId, null), 400, PASSWORD);
    }

    @Test
    void disabledUserRecordsRemainPubliclyReadableWithoutChangingCredentialVerification() throws Exception {
        Map<String, Object> disabled = create("disabledreader", "disabledreader@example.com");
        jdbc.update("UPDATE " + TABLE + " SET ENABLED = 0 WHERE ID = ?", id(disabled));

        HttpResponse<String> profile = get("/userdetails/" + id(disabled), null);
        assertThat(profile.statusCode()).isEqualTo(200);
        assertThat(body(profile)).containsEntry("username", "disabledreader").containsEntry("enabled", false);
        assertNoSecrets(profile, PASSWORD);

        HttpResponse<String> page = get("/userdetails", null);
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(object(((List<?>) body(page).get("content")).getFirst())).containsEntry("enabled", false);
        assertNoSecrets(page, PASSWORD);

        assertSafeError(post("/authuserdetails", credentials("disabledreader", PASSWORD)), 401, PASSWORD);
    }

    @Test
    void publicReadsIgnoreStaleMalformedUnknownAndDisabledBasicCredentials() throws Exception {
        Map<String, Object> existing = create("existingreader", "existingreader@example.com");
        Map<String, Object> disabled = create("disabledreader", "disabledreader@example.com");
        jdbc.update("UPDATE " + TABLE + " SET ENABLED = 0 WHERE ID = ?", id(disabled));

        for (String authorization : List.of(
                basic("existingreader", "StalePassword9!"),
                "Basic not-valid-base64!",
                basic("unknownreader", PASSWORD),
                basic("disabledreader", PASSWORD))) {
            for (String path : List.of("/userdetails", "/userdetails/" + id(existing))) {
                HttpResponse<String> response = get(path, authorization);
                assertThat(response.statusCode()).as("public GET %s must not authenticate", path).isEqualTo(200);
                assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
                assertNoSecrets(response, PASSWORD, "StalePassword9!");
            }
        }
    }

    @Test
    void anonymousReaderCanPageThroughSafeProfilesWithoutAnAdminAccount() throws Exception {
        create("first", "first@example.com");
        Map<String, Object> second = create("second", "second@example.com");
        Map<String, Object> third = create("third", "third@example.com");

        HttpResponse<String> profile = get("/userdetails/" + id(second), null);
        assertThat(profile.statusCode()).isEqualTo(200);
        assertUser(body(profile), "second", "second@example.com", "USER");
        assertNoSecrets(profile, PASSWORD);

        HttpResponse<String> defaultPage = get("/userdetails", null);
        assertThat(defaultPage.statusCode()).isEqualTo(200);
        assertThat(((Number) body(defaultPage).get("page")).intValue()).isZero();
        assertThat(((Number) body(defaultPage).get("size")).intValue()).isEqualTo(20);

        HttpResponse<String> pageResponse = get("/userdetails?page=1&size=2", null);
        assertThat(pageResponse.statusCode()).isEqualTo(200);
        Map<String, Object> page = body(pageResponse);
        assertThat(((Number) page.get("page")).intValue()).isEqualTo(1);
        assertThat(((Number) page.get("size")).intValue()).isEqualTo(2);
        assertThat(((Number) page.get("totalElements")).longValue()).isEqualTo(3);
        List<?> content = (List<?>) page.get("content");
        assertThat(content).hasSize(1);
        Map<String, Object> last = object(content.getFirst());
        assertUser(last, "third", "third@example.com", "USER");
        assertThat(id(last)).isEqualTo(id(third));
        assertNoSecrets(defaultPage, PASSWORD);
        assertNoSecrets(pageResponse, PASSWORD);
    }

    @Test
    void publicPaginationRejectsInvalidBoundsAndAcceptsDocumentedMaximums() throws Exception {
        create("pageuser", "pageuser@example.com");
        for (String query : List.of("page=-1", "page=1000001", "size=0", "size=101", "page=not-a-number")) {
            assertSafeError(get("/userdetails?" + query, null), 400, PASSWORD);
        }
        HttpResponse<String> maximum = get("/userdetails?page=1000000&size=100", null);
        assertThat(maximum.statusCode()).isEqualTo(200);
        assertThat((List<?>) body(maximum).get("content")).isEmpty();
        assertNoSecrets(maximum, PASSWORD);
    }

    @Test
    void actuatorInfoStillRequiresAnEnabledAdminWithValidBasicCredentials() throws Exception {
        Map<String, Object> user = create("infoadmin", "infoadmin@example.com");

        HttpResponse<String> anonymous = get("/actuator/info", null);
        assertSafeError(anonymous, 401, PASSWORD);
        assertThat(anonymous.headers().firstValue("WWW-Authenticate").orElse("")).startsWith("Basic");
        assertSafeError(get("/actuator/info", basic("infoadmin", PASSWORD)), 403, PASSWORD);
        assertSafeError(get("/actuator/info", basic("infoadmin", "WrongSecret9!")), 401, "WrongSecret9!");
        assertSafeError(get("/actuator/info", "Basic not-valid-base64!"), 401, PASSWORD);

        promoteAdmin(id(user));
        HttpResponse<String> admin = get("/actuator/info", basic("infoadmin", PASSWORD));
        assertThat(admin.statusCode()).isEqualTo(200);
        assertNoSecrets(admin, PASSWORD);

        jdbc.update("UPDATE " + TABLE + " SET ENABLED = 0 WHERE ID = ?", id(user));
        assertSafeError(get("/actuator/info", basic("infoadmin", PASSWORD)), 401, PASSWORD);
    }

    @Test
    void publicReadChainDoesNotOpenUnrelatedRoutesNestedPathsOrWriteMethods() throws Exception {
        Map<String, Object> user = create("routeadmin", "routeadmin@example.com");
        promoteAdmin(id(user));
        String authorization = basic("routeadmin", PASSWORD);

        for (String path : List.of("/not-a-public-route", "/userdetails/" + id(user) + "/extra")) {
            assertSafeError(get(path, null), 401, PASSWORD);
            assertSafeError(get(path, authorization), 403, PASSWORD);
        }
        for (String method : List.of("PUT", "PATCH", "DELETE")) {
            assertSafeError(send(method, "/userdetails/" + id(user), null, null), 403, PASSWORD);
        }
        assertThat(countUsers()).isEqualTo(1);
    }

    @Test
    void publicRegistrationCannotMassAssignAdminRole() throws Exception {
        Map<String, Object> request = registration("untrusted", "untrusted@example.com");
        request.put("role", "ADMIN");
        request.put("userRole", "ADMIN");

        HttpResponse<String> response = post("/userdetails", request);

        // Rejecting unknown properties and ignoring them are both safe API policies.
        assertThat(response.statusCode()).isIn(201, 400);
        if (response.statusCode() == 400) {
            assertSafeError(response, 400, PASSWORD);
            assertThat(countUsers()).isZero();
            return;
        }
        assertUser(body(response), "untrusted", "untrusted@example.com", "USER");
        assertThat(jdbc.queryForObject("SELECT USER_ROLE FROM " + TABLE + " WHERE ID = ?",
                String.class, id(body(response)))).isEqualTo("USER");
        assertSafeError(get("/actuator/info", basic("untrusted", PASSWORD)), 403, PASSWORD);
    }

    private Map<String, Object> create(String username, String email) throws Exception {
        HttpResponse<String> response = post("/userdetails", registration(username, email));
        assertThat(response.statusCode()).as("registration response: %s", response.body()).isEqualTo(201);
        return body(response);
    }

    private Map<String, Object> registration(String username, String email) {
        return new HashMap<>(Map.of("username", username, "password", PASSWORD, "email", email, "phoneNumber", PHONE));
    }

    private Map<String, Object> credentials(String username, String password) {
        return Map.of("username", username, "password", password);
    }

    private void promoteAdmin(long id) {
        jdbc.update("UPDATE " + TABLE + " SET USER_ROLE = 'ADMIN' WHERE ID = ?", id);
    }

    private long countUsers() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + TABLE, Long.class);
    }

    private long id(Map<String, Object> user) {
        return ((Number) user.get("id")).longValue();
    }

    private void assertUser(Map<String, Object> user, String username, String email, String role) {
        assertThat(user).containsOnlyKeys("id", "username", "email", "phoneNumber", "role", "enabled", "createdAt", "modifiedAt");
        assertThat(id(user)).isPositive();
        assertThat(user).containsEntry("username", username).containsEntry("email", email)
                .containsEntry("phoneNumber", PHONE).containsEntry("role", role).containsEntry("enabled", true);
        assertThat(OffsetDateTime.parse((String) user.get("createdAt"))).isNotNull();
        assertThat(OffsetDateTime.parse((String) user.get("modifiedAt"))).isNotNull();
    }

    private void assertSafeError(HttpResponse<String> response, int status, String... secrets) {
        assertThat(response.statusCode()).as("HTTP response: %s", response.body()).isEqualTo(status);
        Map<String, Object> error = body(response);
        assertThat(error).containsKeys("code", "message", "errors", "timestamp");
        assertThat(error.get("code")).isInstanceOf(String.class);
        assertThat((String) error.get("message")).isNotBlank();
        assertThat(error.get("errors")).isInstanceOf(Map.class);
        assertThat(error.get("timestamp")).isNotNull();
        assertNoSecrets(response, secrets);
    }

    private void assertNoSecrets(HttpResponse<String> response, String... secrets) {
        assertThat(response.body()).doesNotContain("passwordHash", "password_hash", "PASSWORD_HASH", "$2a$", "$2b$", "$2y$");
        for (String secret : secrets) {
            assertThat(response.body()).doesNotContain(secret);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(HttpResponse<String> response) {
        return json.readValue(response.body(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> object(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    private HttpResponse<String> post(String path, Map<String, Object> request) throws Exception {
        return send("POST", path, json.writeValueAsString(request), null);
    }

    private HttpResponse<String> get(String path, String authorization) throws Exception {
        return send("GET", path, null, authorization);
    }

    private HttpResponse<String> send(String method, String path, String requestBody, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUri() + path))
                .timeout(Duration.ofSeconds(15)).header("Accept", "application/json");
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        if (requestBody != null) {
            request.header("Content-Type", "application/json");
        }
        return client.send(request.method(method, requestBody == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(requestBody)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private String basic(String username, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private String baseUri() {
        return "http://127.0.0.1:" + port + "/userInfoServices";
    }
}
