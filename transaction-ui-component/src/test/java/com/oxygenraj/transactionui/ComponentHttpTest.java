package com.oxygenraj.transactionui;

import com.oxygenraj.uiplatform.*;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"eureka.client.enabled=false"})
class ComponentHttpTest {
    @LocalServerPort int port;
    @MockitoBean UiPlatform platform;
    @MockitoBean ComponentBackend backend;
    private HttpClient http;
    private final SessionPrincipal principal=new SessionPrincipal(17,"sample.user");
    @BeforeEach void setup() {
        http=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        when(platform.authenticate(any())).thenThrow(new UiPlatformException(401,"UNAUTHORIZED","Sign in again."));
        doReturn(principal).when(platform).authenticate("Bearer valid-token");
        when(backend.activeUser(17)).thenReturn(new ComponentBackend.User(17,"sample.user"));
        when(backend.users(0,100)).thenReturn(new ComponentBackend.Page<>(List.of(new ComponentBackend.User(1,"admin")),0,100,1,1));
        when(backend.transactions(1,0,20)).thenReturn(new ComponentBackend.Page<>(List.of(new ComponentBackend.Transaction(2,1,"JANUARY",1,new BigDecimal("1000.25"),"2026-01-01T00:00:00Z","2026-01-01T00:00:00Z",0)),0,20,1,1));
        when(backend.transactions(17,0,20)).thenReturn(new ComponentBackend.Page<>(List.of(new ComponentBackend.Transaction(3,17,"FEBRUARY",2,new BigDecimal("2100.50"),"2026-02-01T00:00:00Z","2026-02-01T00:00:00Z",0)),0,20,1,1));
    }
    @AfterEach void close() { http.close(); }
    @Test void deploysAndDescribesHostWithoutBaseUiOrDatabase() throws Exception {
        assertThat(get("/",null).statusCode()).isEqualTo(200);
        assertThat(get("/actuator/health",null).statusCode()).isEqualTo(200);
        verifyNoInteractions(backend,platform);
    }
    @Test void moduleAssetsArePublicButNoStandaloneScreenIsServed() throws Exception {
        assertThat(get("/js/components/transactions/loader.js",null).statusCode()).isEqualTo(200);
        assertThat(get("/js/components/transactions/styles.css",null).statusCode()).isEqualTo(200);
        assertThat(get("/",null).body()).contains("user-info-ui").doesNotContain("<html");
    }
    @ParameterizedTest @ValueSource(strings={"/api/users","/api/transactions?userId=1","/api/current-user","/api/my-transactions?userId=17"})
    void anonymousOrForgedIdentityCannotRetrieveData(String path) throws Exception {
        assertThat(get(path,null).statusCode()).isEqualTo(401);
        assertThat(get(path,"Bearer forged").statusCode()).isEqualTo(401);
        verifyNoInteractions(backend);
    }
    @ParameterizedTest @ValueSource(strings={"/api/current-user","/api/my-transactions?userId=17"})
    void expiredSessionCannotRetrieveSelfData(String path) throws Exception {
        doThrow(new UiPlatformException(401,"SESSION_EXPIRED","Sign in again.")).when(platform).authenticate("Bearer expired-token");
        assertThat(get(path,"Bearer expired-token").statusCode()).isEqualTo(401);
        verifyNoInteractions(backend);
        verify(platform,never()).requireFunctional(any(),anyString());
    }
    @Test void directApiWorksWithoutBaseAndChecksFunctionalPermission() throws Exception {
        var response=get("/api/transactions?userId=1","Bearer valid-token");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("JANUARY","1000.25");
        verify(platform).requireFunctional(principal,"TXN_LIST_READ");
        verify(backend).transactions(1,0,20);
    }
    @Test void userListUsesCallerIdentityNotSelectedUserId() throws Exception {
        assertThat(get("/api/users","Bearer valid-token").statusCode()).isEqualTo(200);
        verify(platform).requireFunctional(principal,"TXN_USERS_READ");
        verify(backend).activeUser(17);
    }
    @Test void currentUserReturnsOnlyVerifiedCallerWithoutUsingSelectedUserId() throws Exception {
        var response=get("/api/current-user?userId=1","Bearer valid-token");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"items\":[{\"id\":17,\"username\":\"sample.user\"}]","\"page\":0","\"size\":1","\"totalElements\":1","\"totalPages\":1");
        verify(platform).requireFunctional(principal,"TXN_AI_SELF_READ");
        verify(backend).activeUser(17);
        verify(backend,never()).activeUser(1);
        verify(backend,never()).users(anyInt(),anyInt());
        verify(backend,never()).transactions(anyLong(),anyInt(),anyInt());
    }
    @ParameterizedTest @CsvSource({"0,100","1,1","1000000,100"})
    void currentUserPageKeepsSingletonTotalsAndIsEmptyAfterFirstPage(int page,int size) throws Exception {
        var response=get("/api/current-user?page="+page+"&size="+size,"Bearer valid-token");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"page\":"+page,"\"size\":"+size,"\"totalElements\":1","\"totalPages\":1");
        assertThat(response.body()).contains(page==0?"\"items\":[{\"id\":17,\"username\":\"sample.user\"}]":"\"items\":[]");
        verify(backend).activeUser(17);
        verify(backend,never()).users(anyInt(),anyInt());
    }
    @Test void selfTransactionsUseVerifiedCallerAndDedicatedFunctionalPermission() throws Exception {
        var response=get("/api/my-transactions?userId=17","Bearer valid-token");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("FEBRUARY","2100.50","\"userId\":17","\"size\":20");
        verify(platform).requireFunctional(principal,"TXN_AI_SELF_READ");
        verify(platform,never()).requireFunctional(principal,"TXN_USERS_READ");
        verify(platform,never()).requireFunctional(principal,"TXN_LIST_READ");
        verify(backend).transactions(17,0,20);
    }
    @ParameterizedTest @CsvSource({"0,1","2,25","1000000,100"})
    void selfTransactionsForwardValidPagination(int page,int size) throws Exception {
        when(backend.transactions(17,page,size)).thenReturn(new ComponentBackend.Page<>(List.of(),page,size,0,0));
        var response=get("/api/my-transactions?userId=17&page="+page+"&size="+size,"Bearer valid-token");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"page\":"+page,"\"size\":"+size);
        verify(backend).transactions(17,page,size);
    }
    @Test void forgedSelfTransactionUserIdIsForbiddenBeforeTransactionRead() throws Exception {
        var response=get("/api/my-transactions?userId=1","Bearer valid-token");
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("FORBIDDEN");
        verify(backend).activeUser(17);
        verify(backend,never()).transactions(anyLong(),anyInt(),anyInt());
        verify(backend,never()).users(anyInt(),anyInt());
    }
    @ParameterizedTest @ValueSource(strings={"/api/current-user","/api/my-transactions?userId=17"})
    void selfEntitlementDenyPreventsDataRead(String path) throws Exception {
        doThrow(new UiPlatformException(403,"FORBIDDEN","Not allowed.")).when(platform).requireFunctional(principal,"TXN_AI_SELF_READ");
        assertThat(get(path,"Bearer valid-token").statusCode()).isEqualTo(403);
        verify(backend,never()).transactions(anyLong(),anyInt(),anyInt());
        verify(backend,never()).users(anyInt(),anyInt());
    }
    @Test void entitlementDenyPreventsBackendRead() throws Exception {
        doThrow(new UiPlatformException(403,"FORBIDDEN","Not allowed.")).when(platform).requireFunctional(principal,"TXN_LIST_READ");
        assertThat(get("/api/transactions?userId=1","Bearer valid-token").statusCode()).isEqualTo(403);
        verify(backend,never()).transactions(anyLong(),anyInt(),anyInt());
    }
    @ParameterizedTest @ValueSource(strings={"/api/users","/api/current-user","/api/my-transactions?userId=17"})
    void renamedOrReusedIdentityInvalidatesSession(String path) throws Exception {
        when(backend.activeUser(17)).thenReturn(new ComponentBackend.User(17,"someone.else"));
        assertThat(get(path,"Bearer valid-token").statusCode()).isEqualTo(401);
        verify(platform).revoke("Bearer valid-token");
        verify(platform,never()).requireFunctional(any(),anyString());
        verify(backend,never()).users(anyInt(),anyInt());
        verify(backend,never()).transactions(anyLong(),anyInt(),anyInt());
    }
    @ParameterizedTest @ValueSource(strings={"/api/current-user","/api/my-transactions?userId=17"})
    void mismatchedActiveUserIdInvalidatesSelfSession(String path) throws Exception {
        when(backend.activeUser(17)).thenReturn(new ComponentBackend.User(18,"sample.user"));
        assertThat(get(path,"Bearer valid-token").statusCode()).isEqualTo(401);
        verify(platform).revoke("Bearer valid-token");
        verify(platform,never()).requireFunctional(any(),anyString());
        verify(backend,never()).transactions(anyLong(),anyInt(),anyInt());
    }
    @ParameterizedTest @ValueSource(strings={"/api/users","/api/current-user","/api/my-transactions?userId=17"})
    void disabledAccountCannotUsePreviouslyIssuedSession(String path) throws Exception {
        when(backend.activeUser(17)).thenThrow(new ApiFailure(401,"ACCOUNT_DISABLED","Account disabled."));
        assertThat(get(path,"Bearer valid-token").statusCode()).isEqualTo(401);
        verify(platform).revoke("Bearer valid-token");
        verify(backend,never()).users(anyInt(),anyInt());
        verify(backend,never()).transactions(anyLong(),anyInt(),anyInt());
    }
    @ParameterizedTest @ValueSource(strings={"/api/current-user","/api/my-transactions?userId=17"})
    void deletedAccountInvalidatesSelfSession(String path) throws Exception {
        when(backend.activeUser(17)).thenThrow(new ApiFailure(404,"NOT_FOUND","User not found."));
        assertThat(get(path,"Bearer valid-token").statusCode()).isEqualTo(401);
        verify(platform).revoke("Bearer valid-token");
        verify(platform,never()).requireFunctional(any(),anyString());
        verify(backend,never()).transactions(anyLong(),anyInt(),anyInt());
    }
    @ParameterizedTest @ValueSource(strings={"/api/transactions","/api/transactions?userId=0","/api/transactions?userId=-1","/api/transactions?userId=abc","/api/users?page=-1","/api/users?size=101","/api/users?size=0","/api/users?page=2147483648"})
    void invalidInputsRejected(String path) throws Exception { assertThat(get(path,"Bearer valid-token").statusCode()).isEqualTo(400); }
    @ParameterizedTest @ValueSource(strings={"/api/current-user?page=-1","/api/current-user?page=1000001","/api/current-user?page=abc","/api/current-user?size=0","/api/current-user?size=101","/api/current-user?size=abc","/api/my-transactions","/api/my-transactions?userId=0","/api/my-transactions?userId=-1","/api/my-transactions?userId=abc","/api/my-transactions?userId=9223372036854775808","/api/my-transactions?userId=17&page=-1","/api/my-transactions?userId=17&page=1000001","/api/my-transactions?userId=17&size=0","/api/my-transactions?userId=17&size=101"})
    void invalidSelfInputsAreRejectedBeforeBackendAccess(String path) throws Exception {
        assertThat(get(path,"Bearer valid-token").statusCode()).isEqualTo(400);
        verifyNoInteractions(backend);
    }
    @Test void signinIssuesSharedSessionWithNoCookieOrPasswordEcho() throws Exception {
        when(backend.signIn("sample.user","Test-only-password")).thenReturn(new ComponentBackend.User(17,"sample.user"));
        when(platform.issueSession(17,"sample.user")).thenReturn(new IssuedSession("issued-test-token",Instant.now().plusSeconds(3600)));
        var result=post("/api/sign-in","{\"username\":\"sample.user\",\"password\":\"Test-only-password\"}",null);
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.body()).contains("issued-test-token","expiresAt").doesNotContain("Test-only-password");
        assertThat(result.headers().allValues("Set-Cookie")).isEmpty();
    }
    @Test void invalidCredentialsNeverIssueSession() throws Exception {
        when(backend.signIn(anyString(),anyString())).thenThrow(new ApiFailure(401,"INVALID_CREDENTIALS","Invalid credentials."));
        assertThat(post("/api/sign-in","{\"username\":\"test\",\"password\":\"incorrect\"}",null).statusCode()).isEqualTo(401);
        verify(platform,never()).issueSession(anyLong(),anyString());
    }
    @Test void signoutRevokesSharedSession() throws Exception {
        assertThat(post("/api/sign-out","{}","Bearer valid-token").statusCode()).isEqualTo(204);
        verify(platform).revoke("Bearer valid-token");
    }
    @Test void noWriteApiOrCorsExposure() throws Exception {
        assertThat(post("/api/transactions","{}","Bearer valid-token").statusCode()).isEqualTo(405);
        assertThat(get("/api/users","Bearer valid-token").headers().allValues("Access-Control-Allow-Origin")).isEmpty();
    }
    private HttpResponse<String> get(String path,String auth) throws Exception { return send(path,null,auth); }
    private HttpResponse<String> post(String path,String body,String auth) throws Exception { return send(path,body,auth); }
    private HttpResponse<String> send(String path,String body,String auth) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/transaction-ui-component"+path));
        if(auth!=null) b.header("Authorization",auth);
        if(body==null) b.GET(); else b.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
}
