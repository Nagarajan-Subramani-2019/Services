package com.oxygenraj.transactionui;

import com.oxygenraj.uiplatform.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class ComponentController {
    private final UiPlatform platform;
    private final ComponentBackend backend;
    public ComponentController(UiPlatform platform,ComponentBackend backend) { this.platform=platform; this.backend=backend; }
    public record SignIn(@NotBlank @Size(max=64) String username,@NotBlank @Size(max=72) String password) {
        @Override public String toString() { return "SignIn[credentials=REDACTED]"; }
    }
    public record Login(boolean authenticated,ComponentBackend.User user,String accessToken,Instant expiresAt) {
        @Override public String toString() { return "Login[authenticated="+authenticated+",accessToken=REDACTED]"; }
    }
    @GetMapping("/")
    Map<String,String> about() { return Map.of("component","transactions","uiHost","user-info-ui","message","Open user-info-ui to use the screen. Component APIs are independently available."); }
    @PostMapping(value="/api/sign-in",consumes="application/json")
    Login signIn(@Valid @RequestBody SignIn request) {
        if(request.password().getBytes(StandardCharsets.UTF_8).length>72) throw new ApiFailure(400,"INVALID_REQUEST","Password is too long.");
        var user=backend.signIn(request.username(),request.password());
        var session=platform.issueSession(user.id(),user.username());
        return new Login(true,user,session.accessToken(),session.expiresAt());
    }
    @PostMapping("/api/sign-out")
    ResponseEntity<Void> signOut(@RequestHeader(value="Authorization",required=false) String header) {
        platform.revoke(header); return ResponseEntity.noContent().build();
    }
    @GetMapping("/api/users")
    ComponentBackend.Page<ComponentBackend.User> users(@RequestHeader(value="Authorization",required=false) String header,
            @RequestParam(defaultValue="0") @Min(0) @Max(1000000) int page,
            @RequestParam(defaultValue="100") @Min(1) @Max(100) int size) {
        authorize(header,"TXN_USERS_READ"); return backend.users(page,size);
    }
    @GetMapping("/api/transactions")
    ComponentBackend.Page<ComponentBackend.Transaction> transactions(@RequestHeader(value="Authorization",required=false) String header,
            @RequestParam @Positive long userId,
            @RequestParam(defaultValue="0") @Min(0) @Max(1000000) int page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) int size) {
        authorize(header,"TXN_LIST_READ"); return backend.transactions(userId,page,size);
    }
    @GetMapping("/api/current-user")
    ComponentBackend.Page<ComponentBackend.User> currentUser(@RequestHeader(value="Authorization",required=false) String header,
            @RequestParam(defaultValue="0") @Min(0) @Max(1000000) int page,
            @RequestParam(defaultValue="1") @Min(1) @Max(100) int size) {
        var user=authorize(header,"TXN_AI_SELF_READ");
        return new ComponentBackend.Page<>(page==0?List.of(user):List.of(),page,size,1,1);
    }
    @GetMapping("/api/my-transactions")
    ComponentBackend.Page<ComponentBackend.Transaction> myTransactions(@RequestHeader(value="Authorization",required=false) String header,
            @RequestParam @Positive long userId,
            @RequestParam(defaultValue="0") @Min(0) @Max(1000000) int page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) int size) {
        var user=authorize(header,"TXN_AI_SELF_READ");
        if(userId!=user.id()) throw new ApiFailure(403,"FORBIDDEN","Only your own transactions are available.");
        return backend.transactions(user.id(),page,size);
    }
    private ComponentBackend.User authorize(String header,String code) {
        var principal=platform.authenticate(header);
        ComponentBackend.User user;
        try { user=backend.activeUser(principal.userId()); }
        catch(ApiFailure e) {
            if(e.status()==401 || e.status()==404) {
                platform.revoke(header);
                throw new ApiFailure(401,"SESSION_INVALID","Sign in again.");
            }
            throw e;
        }
        if(user.id()!=principal.userId() || !user.username().equals(principal.username())) {
            platform.revoke(header); throw new ApiFailure(401,"SESSION_INVALID","Sign in again.");
        }
        // API permission is independent of the UI permission: hiding a menu is not API authorization.
        platform.requireFunctional(principal,code);
        return user;
    }
}
