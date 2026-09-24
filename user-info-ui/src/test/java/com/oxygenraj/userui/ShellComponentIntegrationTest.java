package com.oxygenraj.userui;

import com.oxygenraj.uiplatform.IssuedSession;
import com.oxygenraj.uiplatform.MenuItem;
import com.oxygenraj.uiplatform.SessionPrincipal;
import com.oxygenraj.uiplatform.UiPlatform;
import com.oxygenraj.uiplatform.UiPlatformException;
import com.oxygenraj.uiplatform.ComponentDefinition;
import com.oxygenraj.uiplatform.ComponentApi;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "server.servlet.context-path=/user-info-ui", "eureka.client.enabled=false",
        "user-info.client.mode=direct", "ui.component-client.mode=direct"
})
class ShellComponentIntegrationTest {
    private static final StubUserInfoServer USERS = new StubUserInfoServer();
    private static final StubUserInfoServer COMPONENT = new StubUserInfoServer();
    private static final String AUTHORIZATION = "Bearer synthetic-shell-token";
    private static final SessionPrincipal PRINCIPAL = new SessionPrincipal(17L, "sample.user");
    private static final String USER_PAGE = """
            {"items":[{"id":17,"username":"sample.user","passwordHash":"must-not-leak","email":"not-projected@test.local"}],
             "page":0,"size":20,"totalElements":1,"totalPages":1}
            """;
    private static final String TRANSACTION_PAGE = """
            {"items":[{"id":9007199254740993,"userId":17,"monthName":"SEPTEMBER","monthCount":2,
              "amount":98765432101234567.89,"createdAt":"2026-09-23T00:00:00Z","modifiedAt":"2026-09-23T00:00:00Z",
              "version":0,"password":"must-not-leak"}],"page":0,"size":20,"totalElements":1,"totalPages":1}
            """;
    @LocalServerPort private int port;
    @Autowired private ObjectMapper json;
    @MockitoBean private UiPlatform platform;
    private HttpClient http;

    @DynamicPropertySource static void addresses(DynamicPropertyRegistry registry) {
        registry.add("user-info.client.base-url", () -> USERS.origin() + "/userInfoServices");
        registry.add("ui.component-client.allowed-origins", COMPONENT::origin);
    }

    @BeforeEach void setup() {
        USERS.reset(); COMPONENT.reset();
        USERS.reply(200, StubUserInfoServer.USER);
        COMPONENT.reply(200, USER_PAGE);
        when(platform.component("transactions")).thenReturn(definition("transactions", "transactions"));
        when(platform.componentApi("transactions", "users")).thenReturn(new ComponentApi("transactions", "users", "/api/users",
                "UI_TRANSACTIONS", "TXN_USERS_READ", "USER_OPTIONS", List.of("page", "size")));
        when(platform.componentApi("transactions", "transactions")).thenReturn(new ComponentApi("transactions", "transactions", "/api/transactions",
                "UI_TRANSACTIONS", "TXN_LIST_READ", "TRANSACTION_PAGE", List.of("userId", "page", "size")));
        when(platform.authenticate(any())).thenAnswer(call -> {
            if (!AUTHORIZATION.equals(call.getArgument(0))) throw new UiPlatformException(401, "SESSION_INVALID", "Please sign in again.");
            return PRINCIPAL;
        });
        when(platform.issueSession(17L, "sample.user")).thenReturn(new IssuedSession("synthetic-shell-token", Instant.parse("2030-01-01T00:00:00Z")));
        when(platform.menu(PRINCIPAL)).thenReturn(List.of(
                new MenuItem("UI_TRANSACTIONS", "transactions", "Transactions", "transactions", 10, true),
                new MenuItem("UI_ITEM1", "placeholder", "Item 1", "placeholder", 20, false)));
        http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(3)).build();
    }
    @AfterEach void closeHttp() { http.close(); }
    @AfterAll static void closeStubs() { USERS.close(); COMPONENT.close(); }

    @Test void signInIssuesOpaqueSessionOnlyAfterExistingBackendVerification() throws Exception {
        USERS.reply(200, "{\"authenticated\":true,\"user\":" + StubUserInfoServer.USER + "}");
        var response = send("POST", "/api/sign-in", null,
                "{\"username\":\"sample.user\",\"password\":\"" + StubUserInfoServer.PASSWORD + "\"}");
        assertThat(response.statusCode()).isEqualTo(200);
        var body = json.readTree(response.body());
        assertThat(body.get("accessToken").asString()).isEqualTo("synthetic-shell-token");
        assertThat(body.get("expiresAt").asString()).isEqualTo("2030-01-01T00:00:00Z");
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        verify(platform).issueSession(17L, "sample.user");
        assertThat(response.body()).doesNotContain(StubUserInfoServer.PASSWORD);
    }

    @Test void cannotIssueSessionForDisabledUserEvenIfUpstreamAuthenticates() throws Exception {
        USERS.reply(200, "{\"authenticated\":true,\"user\":" + StubUserInfoServer.USER.replace("\"enabled\":true", "\"enabled\":false") + "}");
        var response = send("POST", "/api/sign-in", null,
                "{\"username\":\"sample.user\",\"password\":\"" + StubUserInfoServer.PASSWORD + "\"}");
        assertThat(response.statusCode()).isEqualTo(401);
        verify(platform, never()).issueSession(17L, "sample.user");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/menu", "/api/components/transactions/users", "/api/components/transactions/transactions?userId=17"})
    void protectedRoutesRejectAbsentBearerBeforeAnyNetworkCalls(String path) throws Exception {
        var response = send("GET", path, null, null);
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(USERS.requests()).isEmpty();
        assertThat(COMPONENT.requests()).isEmpty();
    }

    @Test void userIdQueryIsNotAuthentication() throws Exception {
        var response = send("GET", "/api/components/transactions/transactions?userId=17&username=sample.user", null, null);
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(COMPONENT.requests()).isEmpty();
    }

    @Test void menuRevalidatesEnabledAccountAndReturnsOnlySharedMenuItems() throws Exception {
        var response = send("GET", "/api/menu", AUTHORIZATION, null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).get("items").size()).isEqualTo(2);
        assertThat(response.body()).contains("UI_TRANSACTIONS", "UI_ITEM1", "\"enabled\":false");
        assertThat(USERS.requests()).hasSize(1);
        assertThat(USERS.requests().getFirst().path()).isEqualTo("/userInfoServices/userdetails/17");
        assertThat(USERS.requests().getFirst().authorization()).isNull();
        verify(platform).menu(PRINCIPAL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/menu", "/api/components/transactions/users"})
    void disabledAccountRevokesSessionAndDoesNotReachComponent(String path) throws Exception {
        USERS.reply(200, StubUserInfoServer.USER.replace("\"enabled\":true", "\"enabled\":false"));
        var response = send("GET", path, AUTHORIZATION, null);
        assertThat(response.statusCode()).isEqualTo(401);
        verify(platform).revoke(AUTHORIZATION);
        assertThat(COMPONENT.requests()).isEmpty();
    }

    @Test void deletedAccountRevokesSession() throws Exception {
        USERS.reply(404, "{}");
        var response = send("GET", "/api/menu", AUTHORIZATION, null);
        assertThat(response.statusCode()).isEqualTo(401);
        verify(platform).revoke(AUTHORIZATION);
    }

    @Test void userServiceOutageFailsClosedWithoutRevokingStillPotentiallyValidSession() throws Exception {
        USERS.reply(503, "internal SQL must not leak");
        var response = send("GET", "/api/components/transactions/users", AUTHORIZATION, null);
        assertThat(response.statusCode()).isEqualTo(503);
        verify(platform, never()).revoke(any());
        assertThat(COMPONENT.requests()).isEmpty();
        assertSafe(response);
    }

    @Test void signOutRevokesWithoutCookiesOrUserDataLookup() throws Exception {
        var response = send("POST", "/api/sign-out", AUTHORIZATION, "");
        assertThat(response.statusCode()).isEqualTo(204);
        verify(platform).revoke(AUTHORIZATION);
        assertThat(USERS.requests()).isEmpty();
        assertThat(COMPONENT.requests()).isEmpty();
    }

    @Test void userSelectorUsesFixedRouteForwardsBearerAndProjectsOnlyIdAndUsername() throws Exception {
        var response = send("GET", "/api/components/transactions/users", AUTHORIZATION, null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(COMPONENT.requests()).hasSize(1);
        var request = COMPONENT.requests().getFirst();
        assertThat(request.path()).isEqualTo("/transaction-ui-component/api/users?page=0&size=20");
        assertThat(request.authorization()).isEqualTo(AUTHORIZATION);
        assertThat(request.method()).isEqualTo("GET");
        assertThat(response.body()).doesNotContain("must-not-leak", "passwordHash", "not-projected");
        assertThat(json.readTree(response.body()).get("items").get(0).get("id").asString()).isEqualTo("17");
        verify(platform).requireUi(PRINCIPAL, "UI_TRANSACTIONS");
        verify(platform).requireFunctional(PRINCIPAL, "TXN_USERS_READ");
    }

    @Test void transactionsPreserveLargeIdsAndDecimalAmountWithoutBrowserRounding() throws Exception {
        COMPONENT.reply(200, TRANSACTION_PAGE);
        var response = send("GET", "/api/components/transactions/transactions?userId=17", AUTHORIZATION, null);
        assertThat(response.statusCode()).isEqualTo(200);
        var item = json.readTree(response.body()).get("items").get(0);
        assertThat(item.get("id").asString()).isEqualTo("9007199254740993");
        assertThat(item.get("amount").asString()).isEqualTo("98765432101234567.89");
        assertThat(item.get("userId").asString()).isEqualTo("17");
        assertThat(response.body()).doesNotContain("must-not-leak", "password");
        assertThat(COMPONENT.requests().getFirst().path()).isEqualTo("/transaction-ui-component/api/transactions?userId=17&page=0&size=20");
        verify(platform).requireFunctional(PRINCIPAL, "TXN_LIST_READ");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"1E+2147483647\"", "\"1E-2147483647\"", "\"1E+18\"", "\"0E+18\"",
            "\"100000000000000000\"", "\"0.001\"", "1E+18", "100000000000000000", "1E-3"})
    void invalidDecimalAmountsFailClosedWithoutReflectingUpstreamValues(String amountLiteral) throws Exception {
        COMPONENT.reply(200, transactionPageWithAmount(amountLiteral));
        var response = send("GET", "/api/components/transactions/transactions?userId=17", AUTHORIZATION, null);
        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo("INVALID_COMPONENT_RESPONSE");
        assertSafe(response);
        assertThat(json.readTree(response.body()).get("message").asString()).isEqualTo("The component returned an unexpected response.");
        assertThat(response.body()).doesNotContain("\"amount\"", "\"items\"");
    }

    @ParameterizedTest @ValueSource(ints = {65, 4096})
    void overlengthQuotedAmountIsRejectedEvenWhenItsNumericValueIsSmall(int length) throws Exception {
        // Leading zeroes keep the actual value valid, isolating the string-length bound.
        String quotedAmount = "\"" + "0".repeat(length - 1) + "1\"";
        COMPONENT.reply(200, transactionPageWithAmount(quotedAmount));
        var response = send("GET", "/api/components/transactions/transactions?userId=17", AUTHORIZATION, null);
        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo("INVALID_COMPONENT_RESPONSE");
        assertSafe(response);
    }

    @ParameterizedTest @MethodSource("validDecimalAmounts")
    void validTinyAndScientificAmountsRemainExactPlainDecimalStrings(String amountLiteral, String expected) throws Exception {
        COMPONENT.reply(200, transactionPageWithAmount(amountLiteral));
        var response = send("GET", "/api/components/transactions/transactions?userId=17", AUTHORIZATION, null);
        assertThat(response.statusCode()).isEqualTo(200);
        var amount = json.readTree(response.body()).get("items").get(0).get("amount");
        assertThat(amount.isString()).isTrue();
        assertThat(amount.asString()).isEqualTo(expected);
    }

    private static Stream<Arguments> validDecimalAmounts() {
        return Stream.of(
                Arguments.of("0.01", "0.01"),
                Arguments.of("1E-2", "0.01"),
                Arguments.of("\"1E-2\"", "0.01"),
                Arguments.of("1E+16", "10000000000000000"),
                Arguments.of("\"1E+16\"", "10000000000000000"),
                Arguments.of("\"9.99E+2\"", "999"),
                Arguments.of("\"99999999999999999.99\"", "99999999999999999.99"),
                Arguments.of("\"" + "0".repeat(63) + "1\"", "1"));
    }

    private static String transactionPageWithAmount(String amountLiteral) {
        return TRANSACTION_PAGE.replace("98765432101234567.89", amountLiteral);
    }

    @Test void rejectsTransactionRowsForDifferentFilterUser() throws Exception {
        COMPONENT.reply(200, TRANSACTION_PAGE.replace("\"userId\":17", "\"userId\":18"));
        assertThat(send("GET", "/api/components/transactions/transactions?userId=17", AUTHORIZATION, null).statusCode()).isEqualTo(502);
    }

    @Test void functionalDenyStopsBeforeComponentCall() throws Exception {
        doThrow(new UiPlatformException(403, "FORBIDDEN", "Access denied.")).when(platform).requireFunctional(PRINCIPAL, "TXN_USERS_READ");
        var response = send("GET", "/api/components/transactions/users", AUTHORIZATION, null);
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(COMPONENT.requests()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/components/transactions/users?page=-1", "/api/components/transactions/users?size=101",
            "/api/components/transactions/users?page=1000001", "/api/components/transactions/transactions?userId=0",
            "/api/components/transactions/transactions?userId=abc", "/api/components/transactions/transactions",
            "/api/components/transactions/users?url=http://invalid.example/steal", "/api/components/transactions/users?page=0&page=1"})
    void invalidFilterIsRejectedBeforeComponentNetwork(String path) throws Exception {
        assertThat(send("GET", path, AUTHORIZATION, null).statusCode()).isEqualTo(400);
        assertThat(COMPONENT.requests()).isEmpty();
    }

    @ParameterizedTest @ValueSource(ints = {301, 302, 307, 308})
    void redirectCannotLeakBearerToAnotherDestination(int status) throws Exception {
        try (var destination = new StubUserInfoServer()) {
            COMPONENT.respond(ignored -> new StubUserInfoServer.Reply(status, "", Map.of("Location", destination.origin() + "/steal")));
            var response = send("GET", "/api/components/transactions/users", AUTHORIZATION, null);
            assertThat(response.statusCode()).isEqualTo(502);
            assertThat(destination.requests()).isEmpty();
            assertThat(response.headers().firstValue("Location")).isEmpty();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"{}", "[]", "not-json", "{\"items\":[]}", "{\"items\":[],\"page\":0,\"size\":20,\"totalElements\":1,\"totalPages\":0}"})
    void malformedComponentDataIsSanitized(String body) throws Exception {
        COMPONENT.reply(200, body);
        var response = send("GET", "/api/components/transactions/users", AUTHORIZATION, null);
        assertThat(response.statusCode()).isEqualTo(502);
        assertSafe(response);
    }

    @Test void componentUnavailableShowsSafeError() throws Exception {
        COMPONENT.reply(503, "ORA-00942 SELECT SECRET password");
        var response = send("GET", "/api/components/transactions/users", AUTHORIZATION, null);
        assertThat(response.statusCode()).isEqualTo(503);
        assertSafe(response);
    }

    @Test void publicModuleLoadsFromFixedAssetPathWithoutForwardingAnyBearer() throws Exception {
        COMPONENT.respond(ignored -> new StubUserInfoServer.Reply(200, "export function mount() {}", Map.of("Content-Type", "text/javascript")));
        var response = send("GET", "/components/transactions/loader.js", AUTHORIZATION, null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("text/javascript");
        assertThat(COMPONENT.requests().getFirst().path()).isEqualTo("/transaction-ui-component/js/components/transactions/loader.js");
        assertThat(COMPONENT.requests().getFirst().authorization()).isNull();
        assertThat(USERS.requests()).isEmpty();
        verify(platform, never()).authenticate(any());
    }

    @Test void publicStylesRequireCssContentType() throws Exception {
        COMPONENT.respond(ignored -> new StubUserInfoServer.Reply(200, "<script>bad</script>", Map.of("Content-Type", "text/html")));
        assertThat(send("GET", "/components/transactions/styles.css", null, null).statusCode()).isEqualTo(502);
    }

    @Test void newComponentAndOperationRouteEntirelyFromRegisteredMetadata() throws Exception {
        when(platform.component("directory")).thenReturn(definition("directory", "user-directory"));
        when(platform.componentApi("directory", "people")).thenReturn(new ComponentApi("directory", "people", "/api/users",
                "UI_DIRECTORY", "DIRECTORY_READ", "USER_OPTIONS", List.of("page", "size")));
        var response = send("GET", "/api/components/directory/people", AUTHORIZATION, null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(COMPONENT.requests().getFirst().path()).isEqualTo("/transaction-ui-component/api/users?page=0&size=20");
        verify(platform).requireUi(PRINCIPAL, "UI_DIRECTORY");
        verify(platform).requireFunctional(PRINCIPAL, "DIRECTORY_READ");
    }

    @Test void registeredResourceNameControlsAssetPathWithoutFrontendOrJavaHardcoding() throws Exception {
        when(platform.component("directory")).thenReturn(definition("directory", "user-directory"));
        COMPONENT.respond(ignored -> new StubUserInfoServer.Reply(200, "export function mount() {}", Map.of("Content-Type", "text/javascript")));
        assertThat(send("GET", "/components/directory/loader.js", null, null).statusCode()).isEqualTo(200);
        assertThat(COMPONENT.requests().getFirst().path()).isEqualTo("/transaction-ui-component/js/components/user-directory/loader.js");
    }

    @Test void unapprovedDatabaseOriginCannotReceiveBearer() throws Exception {
        try (var other = new StubUserInfoServer()) {
            when(platform.component("transactions")).thenReturn(new ComponentDefinition("transactions", "txn-server", "transaction-ui-component",
                    other.origin() + "/transaction-ui-component", "transactions"));
            assertThat(send("GET", "/api/components/transactions/users", AUTHORIZATION, null).statusCode()).isEqualTo(503);
            assertThat(other.requests()).isEmpty();
            assertThat(COMPONENT.requests()).isEmpty();
        }
    }

    @Test void arbitraryAssetNameIsRejectedBeforeRegistryOrNetwork() throws Exception {
        assertThat(send("GET", "/components/transactions/secrets.txt", null, null).statusCode()).isEqualTo(404);
        assertThat(COMPONENT.requests()).isEmpty();
        verify(platform, never()).component("transactions");
    }

    @Test void eightyCharacterComponentAndResourceNamesAreSupported() throws Exception {
        String code = "a".repeat(80);
        when(platform.component(code)).thenReturn(definition(code, code));
        COMPONENT.respond(ignored -> new StubUserInfoServer.Reply(200, "export function mount() {}", Map.of("Content-Type", "text/javascript")));
        assertThat(send("GET", "/components/" + code + "/loader.js", null, null).statusCode()).isEqualTo(200);
        assertThat(COMPONENT.requests().getFirst().path()).contains("/js/components/" + code + "/loader.js");
    }

    @ParameterizedTest @ValueSource(strings = {"/api/users?url=evil", "/api/../private", "https://evil.example/api/users"})
    void malformedRegisteredPathFailsClosed(String path) throws Exception {
        when(platform.componentApi("transactions", "users")).thenReturn(new ComponentApi("transactions", "users", path,
                "UI_TRANSACTIONS", "TXN_USERS_READ", "USER_OPTIONS", List.of("page", "size")));
        assertThat(send("GET", "/api/components/transactions/users", AUTHORIZATION, null).statusCode()).isEqualTo(503);
        assertThat(COMPONENT.requests()).isEmpty();
    }

    @Test void unknownResponseContractFailsClosed() throws Exception {
        when(platform.componentApi("transactions", "users")).thenReturn(new ComponentApi("transactions", "users", "/api/users",
                "UI_TRANSACTIONS", "TXN_USERS_READ", "RAW_JSON", List.of("page", "size")));
        assertThat(send("GET", "/api/components/transactions/users", AUTHORIZATION, null).statusCode()).isEqualTo(503);
        assertThat(COMPONENT.requests()).isEmpty();
    }

    private static ComponentDefinition definition(String code, String resource) {
        return new ComponentDefinition(code, "txn-server", "transaction-ui-component",
                COMPONENT.origin() + "/transaction-ui-component", resource);
    }

    private HttpResponse<String> send(String method, String path, String authorization, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/user-info-ui" + path)).timeout(Duration.ofSeconds(15));
        if (authorization != null) request.header("Authorization", authorization);
        if (method.equals("POST")) request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        else request.GET();
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private static void assertSafe(HttpResponse<String> response) {
        assertThat(response.body()).doesNotContain("ORA-", "SELECT", "SECRET", "internal SQL", "synthetic-shell-token", "java.lang");
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
    }
}
