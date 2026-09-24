package com.oxygenraj.transactionui;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class BackendContractTest {
    JsonGateway gateway;
    ComponentBackend backend;
    ObjectMapper json=new ObjectMapper();
    @BeforeEach void setup() {
        gateway=mock(JsonGateway.class);
        var endpoint=new BackendProperties.Endpoint("direct","http://localhost:12345/service","service","/service");
        backend=new ComponentBackend(new BackendProperties(endpoint,endpoint),gateway);
    }
    @Test void userPageUsesContentAndProjectsOnlyIdUsername() {
        reply("{\"content\":[{\"id\":1,\"username\":\"admin\",\"passwordHash\":\"secret\",\"email\":\"private\"}],\"page\":0,\"size\":100,\"totalElements\":1}");
        var result=backend.users(0,100);
        assertThat(result.items()).containsExactly(new ComponentBackend.User(1,"admin"));
        assertThat(json.writeValueAsString(result)).doesNotContain("secret","private","passwordHash","email");
    }
    @Test void transactionsFilterExactSelectedIdAndPreserveMoneyPrecision() {
        reply(transactions(1));
        var result=backend.transactions(1,0,20);
        assertThat(result.items().getFirst().amount().toPlainString()).isEqualTo("99999999999999999.99");
        verify(gateway).request(any(),eq("/transactions?userId=1&page=0&size=20"),isNull());
    }
    @ParameterizedTest @ValueSource(strings={"1E+2147483647","1E-2147483647","1E+18","100000000000000000"})
    void quotedAmountCannotBypassNumericBackendContract(String value) {
        var result=transactionResponse();
        ((ObjectNode)result.path("items").get(0)).put("amount",value);
        when(gateway.request(any(),anyString(),any())).thenReturn(result);
        assertInvalidAmount();
    }
    @Test void oversizedQuotedAmountIsRejectedEvenIfItsNumericValueIsSmall() {
        var result=transactionResponse();
        ((ObjectNode)result.path("items").get(0)).put("amount","0".repeat(8191)+"1");
        when(gateway.request(any(),anyString(),any())).thenReturn(result);
        assertInvalidAmount();
    }
    @ParameterizedTest @MethodSource("invalidDecimalAmounts")
    void rejectsOutOfRangeDecimalNodeBeforeFormattingOrScaleArithmetic(BigDecimal amount) {
        replyWithAmount(amount);
        assertInvalidAmount();
    }
    private static Stream<BigDecimal> invalidDecimalAmounts() {
        return Stream.of(
            // Direct nodes exercise the adapter even if JSON-parser constraints reject these earlier.
            new BigDecimal(BigInteger.ONE,Integer.MIN_VALUE),
            new BigDecimal(BigInteger.ONE,Integer.MAX_VALUE),
            new BigDecimal("1E+18"),new BigDecimal("0E+18"),
            new BigDecimal("100000000000000000"),new BigDecimal("0.001"));
    }
    @ParameterizedTest @MethodSource("validDecimalAmounts")
    void acceptsTinyAndScientificAmountsWithinOracleDecimalRange(BigDecimal amount,String expected) {
        replyWithAmount(amount);
        assertThat(backend.transactions(1,0,20).items().getFirst().amount().toPlainString()).isEqualTo(expected);
    }
    private static Stream<Arguments> validDecimalAmounts() {
        return Stream.of(
            Arguments.of(new BigDecimal("0.01"),"0.01"),
            Arguments.of(new BigDecimal("1E-2"),"0.01"),
            Arguments.of(new BigDecimal("1E+16"),"10000000000000000"),
            Arguments.of(new BigDecimal("1.25E+2"),"125"),
            Arguments.of(new BigDecimal("99999999999999999.99"),"99999999999999999.99"));
    }
    private void assertInvalidAmount() {
        assertThatThrownBy(()->backend.transactions(1,0,20)).isInstanceOfSatisfying(ApiFailure.class,failure->{
            assertThat(failure.status()).isEqualTo(502);
            assertThat(failure.code()).isEqualTo("INVALID_BACKEND_RESPONSE");
            assertThat(failure.getMessage()).isEqualTo("A required API returned an invalid response.");
        });
    }
    private ObjectNode transactionResponse() {
        return (ObjectNode)json.reader().with(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(transactions(1));
    }
    private void replyWithAmount(BigDecimal amount) {
        var result=transactionResponse();
        ((ObjectNode)result.path("items").get(0)).put("amount",amount);
        when(gateway.request(any(),anyString(),any())).thenReturn(result);
    }
    @Test void upstreamReturningDifferentUserCannotLeakRows() {
        reply(transactions(99));
        assertThatThrownBy(()->backend.transactions(1,0,20)).isInstanceOf(ApiFailure.class);
    }
    @ParameterizedTest @ValueSource(strings={"{}","{\"items\":[],\"page\":0,\"size\":100,\"totalElements\":0}","{\"content\":[],\"page\":1,\"size\":100,\"totalElements\":0}","{\"content\":[],\"page\":0,\"size\":100,\"totalElements\":-1}"})
    void malformedUserResponseFailsClosed(String body) { reply(body); assertThatThrownBy(()->backend.users(0,100)).isInstanceOf(ApiFailure.class); }
    @Test void disabledSigninDoesNotAuthenticate() {
        reply("{\"authenticated\":true,\"user\":{\"id\":1,\"username\":\"admin\",\"enabled\":false}}");
        assertThatThrownBy(()->backend.signIn("admin","not-a-real-password")).isInstanceOf(ApiFailure.class);
    }
    @ParameterizedTest @ValueSource(strings={"file:///etc/passwd","http://user:password@localhost/service","http://localhost/service?redirect=1","http://localhost:0/service","http://localhost/service#fragment"})
    void backendConfigurationRejectsUnsafeUris(String url) {
        assertThatThrownBy(()->new BackendProperties.Endpoint("direct",url,"service","/service")).isInstanceOf(IllegalArgumentException.class);
    }
    private void reply(String body) { when(gateway.request(any(),anyString(),any())).thenReturn(json.reader().with(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(body)); }
    private String transactions(long userId) {
        return "{\"items\":[{\"id\":1,\"userId\":"+userId+",\"monthName\":\"JANUARY\",\"monthCount\":5,\"amount\":99999999999999999.99,\"createdAt\":\"2026-01-01T00:00:00Z\",\"modifiedAt\":\"2026-01-01T00:00:00Z\",\"version\":0}],\"page\":0,\"size\":20,\"totalElements\":1}";
    }
}
