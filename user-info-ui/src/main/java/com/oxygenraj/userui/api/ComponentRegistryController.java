package com.oxygenraj.userui.api;

import com.oxygenraj.uiplatform.UiPlatform;
import com.oxygenraj.userui.client.ComponentRegistryClient;
import com.oxygenraj.userui.client.UpstreamFailure;
import com.oxygenraj.userui.security.AuthorizedSessions;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ComponentRegistryController {
    private final ComponentRegistryClient client;
    private final UiPlatform platform;
    private final AuthorizedSessions sessions;

    public ComponentRegistryController(ComponentRegistryClient client, UiPlatform platform, AuthorizedSessions sessions) {
        this.client = client;
        this.platform = platform;
        this.sessions = sessions;
    }

    @GetMapping("/api/components/{componentCode}/{operation}")
    public Object request(@PathVariable @Pattern(regexp = "[a-z][a-z0-9-]{0,79}") String componentCode,
            @PathVariable @Pattern(regexp = "[a-z][a-z0-9-]{0,79}") String operation,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam MultiValueMap<String, String> parameters) {
        var principal = sessions.requireActive(authorization);
        var component = platform.component(componentCode);
        var api = platform.componentApi(componentCode, operation);
        if (api == null) throw ComponentRegistryClient.configuration();
        ComponentRegistryClient.validate(component, api);
        if (!componentCode.equals(component.componentCode()) || !operation.equals(api.operationCode())) throw ComponentRegistryClient.configuration();
        platform.requireUi(principal, api.uiActivityCode());
        platform.requireFunctional(principal, api.functionalActivityCode());
        return client.request(component, api, authorization, parameters);
    }

    @GetMapping("/components/{componentCode}/{assetName}")
    public ResponseEntity<byte[]> resource(@PathVariable @Pattern(regexp = "[a-z][a-z0-9-]{0,79}") String componentCode,
            @PathVariable String assetName) {
        String type = switch (assetName) {
            case "loader.js" -> "text/javascript;charset=UTF-8";
            case "styles.css" -> "text/css;charset=UTF-8";
            case "manifest.json" -> "application/json";
            default -> throw new UpstreamFailure(404, "NOT_FOUND", "Component resource not found.");
        };
        var component = platform.component(componentCode);
        if (component == null || !componentCode.equals(component.componentCode())) throw ComponentRegistryClient.configuration();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(type)).body(client.asset(component, assetName));
    }
}
