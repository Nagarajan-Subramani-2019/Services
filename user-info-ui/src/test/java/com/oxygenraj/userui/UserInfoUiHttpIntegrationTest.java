package com.oxygenraj.userui;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "server.servlet.context-path=/user-info-ui",
        "user-info.client.mode=direct", "eureka.client.enabled=false"
})
class UserInfoUiHttpIntegrationTest {
    private static final StubUserInfoServer UPSTREAM = new StubUserInfoServer();
    @LocalServerPort private int port;
    @Autowired private ObjectMapper json;
    private HttpClient http;

    @DynamicPropertySource
    static void upstreamAddress(DynamicPropertyRegistry registry) {
        registry.add("user-info.client.base-url", () -> UPSTREAM.origin() + "/userInfoServices");
    }

    @BeforeEach void resetFixture() {
        UPSTREAM.reset();
        http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(3)).build();
    }
    @AfterEach void closeClient() { http.close(); }
    @AfterAll static void closeFixture() { UPSTREAM.close(); }

    @Test void rootServesBuiltFrontendWithoutContactingUserApi() throws Exception {
        HttpResponse<String> response = get("/");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("text/html");
        assertThat(response.body()).contains("<html", "<script");
        assertThat(UPSTREAM.requests()).isEmpty();
    }

    @Test void registrationForwardsOnlyExpectedFieldsAndReturnsCreatedSafeUser() throws Exception {
        UPSTREAM.reply(201, withExtraSecrets(StubUserInfoServer.USER));
        Map<String, Object> input = registration();
        input.put("role", "ADMIN");
        input.put("enabled", false);
        HttpResponse<String> response = post("/api/users", input);
        assertThat(response.statusCode()).isEqualTo(201);
        assertSafeUser(response);
        assertThat(UPSTREAM.requests()).hasSize(1);
        StubUserInfoServer.Request forwarded = UPSTREAM.requests().getFirst();
        assertThat(forwarded.method()).isEqualTo("POST");
        assertThat(forwarded.path()).isEqualTo("/userInfoServices/userdetails");
        assertThat(json.readTree(forwarded.body()).get("username").asString()).isEqualTo("sample.user");
        assertThat(json.readTree(forwarded.body()).get("password").asString()).isEqualTo(StubUserInfoServer.PASSWORD);
        assertThat(forwarded.body()).doesNotContain("ADMIN", "enabled");
        assertThat(forwarded.authorization()).isNull();
    }

    @Test void signInChecksRealUpstreamAndReturnsSafeProfileWithoutSessionCookie() throws Exception {
        UPSTREAM.reply(200, "{\"authenticated\":true,\"user\":" + withExtraSecrets(StubUserInfoServer.USER) + "}");
        HttpResponse<String> response = post("/api/sign-in", credentials());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).get("authenticated").asBoolean()).isTrue();
        assertThat(json.readTree(response.body()).get("user").get("id").asLong()).isEqualTo(17);
        assertNoSecrets(response);
        assertThat(response.body()).doesNotContain("\"password\":");
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(UPSTREAM.requests()).hasSize(1);
        assertThat(UPSTREAM.requests().getFirst().path()).isEqualTo("/userInfoServices/authuserdetails");
        assertThat(UPSTREAM.requests().getFirst().body()).contains(StubUserInfoServer.PASSWORD);
    }

    @Test void profileGetIsPublicAndOmitsUpstreamHash() throws Exception {
        UPSTREAM.reply(200, withExtraSecrets(StubUserInfoServer.USER));
        HttpResponse<String> response = get("/api/users/17");
        assertThat(response.statusCode()).isEqualTo(200);
        assertSafeUser(response);
        assertThat(UPSTREAM.requests()).hasSize(1);
        assertThat(UPSTREAM.requests().getFirst().method()).isEqualTo("GET");
        assertThat(UPSTREAM.requests().getFirst().path()).isEqualTo("/userInfoServices/userdetails/17");
        assertThat(UPSTREAM.requests().getFirst().authorization()).isNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 409})
    void knownUpstreamErrorsHaveMatchingStatusAndSanitizedBody(int status) throws Exception {
        UPSTREAM.reply(status, "{\"message\":\"ORA-00942 SELECT PASSWORD_HASH " + StubUserInfoServer.PASSWORD + "\"}");
        HttpResponse<String> response = post("/api/sign-in", credentials());
        assertThat(response.statusCode()).isEqualTo(status);
        assertSafeError(response);
        if (status == 401) assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo("INVALID_CREDENTIALS");
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503, 504})
    void upstreamServerFailureIsUnavailableWithoutDetails(int upstreamStatus) throws Exception {
        UPSTREAM.reply(upstreamStatus, "internal SQL text " + StubUserInfoServer.PASSWORD);
        HttpResponse<String> response = get("/api/users/17");
        assertThat(response.statusCode()).isEqualTo(503);
        assertSafeError(response);
        assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo("SERVICE_UNAVAILABLE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-json", "{}", "null", "[]", "{\"id\":17}"})
    void invalidUpstreamUserPayloadIsBadGateway(String payload) throws Exception {
        UPSTREAM.reply(200, payload);
        HttpResponse<String> response = get("/api/users/17");
        assertThat(response.statusCode()).isEqualTo(502);
        assertSafeError(response);
    }

    @Test void upstreamFalseAuthenticationCannotBecomeSuccessfulSignIn() throws Exception {
        UPSTREAM.reply(200, "{\"authenticated\":false,\"user\":" + StubUserInfoServer.USER + "}");
        HttpResponse<String> response = post("/api/sign-in", credentials());
        assertThat(response.statusCode()).isEqualTo(502);
        assertSafeError(response);
    }

    @Test void successfulStatusWithNonJsonContentTypeIsRejected() throws Exception {
        UPSTREAM.respond(ignored -> new StubUserInfoServer.Reply(200, StubUserInfoServer.USER,
                Map.of("Content-Type", "text/html")));
        HttpResponse<String> response = get("/api/users/17");
        assertThat(response.statusCode()).isEqualTo(502);
        assertSafeError(response);
    }

    @ParameterizedTest
    @ValueSource(ints = {201, 202, 418})
    void unexpectedUpstreamStatusIsNotPresentedAsSuccess(int status) throws Exception {
        UPSTREAM.reply(status, StubUserInfoServer.USER);
        HttpResponse<String> response = get("/api/users/17");
        assertThat(response.statusCode()).isEqualTo(502);
        assertSafeError(response);
    }

    @ParameterizedTest(name = "invalid upstream field: {0}")
    @MethodSource("invalidUserFields")
    void malformedUserFieldsAreRejected(String field, Object value) throws Exception {
        Map<String, Object> input = new LinkedHashMap<>(json.readValue(StubUserInfoServer.USER, Map.class));
        input.put(field, value);
        UPSTREAM.reply(200, json.writeValueAsString(input));
        HttpResponse<String> response = get("/api/users/17");
        assertThat(response.statusCode()).isEqualTo(502);
        assertSafeError(response);
    }

    static Stream<Arguments> invalidUserFields() {
        return Stream.of(Arguments.of("id", 0), Arguments.of("id", "17"),
                Arguments.of("username", ""), Arguments.of("email", null),
                Arguments.of("phoneNumber", Map.of("unexpected", "object")),
                Arguments.of("enabled", "true"), Arguments.of("createdAt", "yesterday"),
                Arguments.of("modifiedAt", null));
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 303, 307, 308})
    void credentialsAreNeverForwardedToRedirectDestination(int redirectStatus) throws Exception {
        try (StubUserInfoServer destination = new StubUserInfoServer()) {
            destination.reply(200, "{\"authenticated\":true,\"user\":" + StubUserInfoServer.USER + "}");
            UPSTREAM.respond(ignored -> new StubUserInfoServer.Reply(redirectStatus, "redirect",
                    Map.of("Location", destination.origin() + "/stolen-credentials", "Content-Type", "text/plain")));
            HttpResponse<String> response = post("/api/sign-in", credentials());
            assertThat(response.statusCode()).isEqualTo(502);
            assertSafeError(response);
            assertThat(UPSTREAM.requests()).hasSize(1);
            assertThat(destination.requests()).isEmpty();
            assertThat(response.headers().firstValue("Location")).isEmpty();
        }
    }

    @ParameterizedTest(name = "invalid registration: {0} / {1}")
    @MethodSource("invalidRegistration")
    void invalidRegistrationIsRejectedBeforeSendingToBackend(String field, String description, Object value) throws Exception {
        Map<String, Object> input = registration();
        input.put(field, value);
        HttpResponse<String> response = post("/api/users", input);
        assertThat(response.statusCode()).isEqualTo(400);
        assertSafeError(response);
        assertThat(UPSTREAM.requests()).isEmpty();
    }

    static Stream<Arguments> invalidRegistration() {
        return Stream.of(
                Arguments.of("username", "missing", null),
                Arguments.of("username", "too short", "ab"),
                Arguments.of("username", "too long", "a".repeat(65)),
                Arguments.of("username", "leading punctuation", ".name"),
                Arguments.of("username", "whitespace", "sample user"),
                Arguments.of("password", "missing", null),
                Arguments.of("password", "blank", " ".repeat(10)),
                Arguments.of("password", "too short", "short1234"),
                Arguments.of("password", "too many ASCII bytes", "a".repeat(73)),
                Arguments.of("password", "too many UTF-8 bytes", "\uD83D\uDE00".repeat(19)),
                Arguments.of("email", "missing", null),
                Arguments.of("email", "invalid", "not-an-email"),
                Arguments.of("phoneNumber", "missing", null),
                Arguments.of("phoneNumber", "short", "123456"),
                Arguments.of("phoneNumber", "format", "+91 9876543210"));
    }

    @ParameterizedTest(name = "invalid sign-in: {0} / {1}")
    @MethodSource("invalidSignIn")
    void invalidSignInIsRejectedBeforeSendingCredentials(String field, String description, Object value) throws Exception {
        Map<String, Object> input = new LinkedHashMap<>(credentials());
        input.put(field, value);
        HttpResponse<String> response = post("/api/sign-in", input);
        assertThat(response.statusCode()).isEqualTo(400);
        assertSafeError(response);
        assertThat(UPSTREAM.requests()).isEmpty();
    }

    static Stream<Arguments> invalidSignIn() {
        return Stream.of(Arguments.of("username", "missing", null),
                Arguments.of("username", "too long", "a".repeat(65)),
                Arguments.of("password", "missing", null), Arguments.of("password", "blank", "  "),
                Arguments.of("password", "too long", "a".repeat(73)),
                Arguments.of("password", "too many UTF-8 bytes", "\uD83D\uDE00".repeat(19)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{}", "{", "[]"})
    void missingOrMalformedJsonIsRejectedWithoutForwarding(String body) throws Exception {
        HttpResponse<String> response = rawPost("/api/users", body);
        assertThat(response.statusCode()).isEqualTo(400);
        assertSafeError(response);
        assertThat(UPSTREAM.requests()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "not-a-number", "999999999999999999999999"})
    void invalidProfileIdIsRejectedWithoutForwarding(String id) throws Exception {
        HttpResponse<String> response = get("/api/users/" + id);
        assertThat(response.statusCode()).isEqualTo(400);
        assertSafeError(response);
        assertThat(UPSTREAM.requests()).isEmpty();
    }

    @Test void apiResponsesCarryNoStoreAndSecurityHeaders() throws Exception {
        UPSTREAM.reply(200, StubUserInfoServer.USER);
        HttpResponse<String> response = get("/api/users/17");
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(response.headers().firstValue("Content-Security-Policy").orElse("")).contains("default-src 'self'");
        assertThat(response.headers().allValues("Access-Control-Allow-Origin")).doesNotContain("*");
    }

    @Test void oversizedUpstreamPayloadIsRejected() throws Exception {
        UPSTREAM.reply(200, "{\"padding\":\"" + "x".repeat(270_000) + "\"}");
        HttpResponse<String> response = get("/api/users/17");
        assertThat(response.statusCode()).isEqualTo(502);
        assertSafeError(response);
    }

    private Map<String, Object> registration() {
        Map<String, Object> value = new LinkedHashMap<>(credentials());
        value.put("email", "sample@example.test");
        value.put("phoneNumber", "+919876543210");
        return value;
    }
    private Map<String, Object> credentials() {
        return Map.of("username", "sample.user", "password", StubUserInfoServer.PASSWORD);
    }
    private String base() { return "http://127.0.0.1:" + port + "/user-info-ui"; }
    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(15)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> post(String path, Object body) throws Exception { return rawPost(path, json.writeValueAsString(body)); }
    private HttpResponse<String> rawPost(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
    private void assertSafeUser(HttpResponse<String> response) throws Exception {
        assertThat(json.readTree(response.body()).get("id").asLong()).isEqualTo(17);
        assertThat(json.readTree(response.body()).get("username").asString()).isEqualTo("sample.user");
        assertThat(json.readTree(response.body()).get("role").asString()).isEqualTo("USER");
        assertNoSecrets(response);
        assertThat(response.body()).doesNotContain("\"password\":");
    }
    private void assertSafeError(HttpResponse<String> response) throws Exception {
        assertThat(json.readTree(response.body()).get("code").asString()).isNotBlank();
        assertThat(json.readTree(response.body()).get("message").asString()).isNotBlank();
        assertThat(response.body()).doesNotContain("ORA-00942", "SELECT ", "internal SQL", "java.lang", "stackTrace");
        assertNoSecrets(response);
    }
    private void assertNoSecrets(HttpResponse<String> response) {
        assertThat(response.body()).doesNotContain(StubUserInfoServer.PASSWORD, "synthetic-hash-value", "\"passwordHash\"");
    }
    private String withExtraSecrets(String user) {
        return user.strip().substring(0, user.strip().length() - 1)
                + ",\"password\":\"" + StubUserInfoServer.PASSWORD + "\",\"passwordHash\":\"synthetic-hash-value\"}";
    }
}
