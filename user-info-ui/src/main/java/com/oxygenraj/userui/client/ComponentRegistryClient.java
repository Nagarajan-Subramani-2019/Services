package com.oxygenraj.userui.client;

import com.oxygenraj.userui.config.ComponentClientProperties;
import com.oxygenraj.userui.config.UserInfoClientProperties;
import com.oxygenraj.uiplatform.ComponentDefinition;
import com.oxygenraj.uiplatform.ComponentApi;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

/** Allowlisted component adapter, never an arbitrary-URL proxy. */
@Service
public class ComponentRegistryClient {
    private static final int MAX_BYTES = 1024 * 1024;
    private final ComponentClientProperties properties;
    private final ObjectProvider<DiscoveryClient> discoveries;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public ComponentRegistryClient(ComponentClientProperties properties,
            ObjectProvider<DiscoveryClient> discoveries, ObjectMapper mapper) {
        this.properties = properties;
        this.discoveries = discoveries;
        this.mapper = mapper;
    }

    @PreDestroy public void close() { http.shutdownNow(); }

    public Object request(ComponentDefinition component, ComponentApi api, String authorization, MultiValueMap<String, String> parameters) {
        validate(component, api);
        if (api == null || api.responseType() == null) throw configuration();
        Set<String> expected = switch (api.responseType()) {
            case "USER_OPTIONS" -> Set.of("page", "size");
            case "TRANSACTION_PAGE" -> Set.of("page", "size", "userId");
            default -> throw configuration();
        };
        if (api.queryParameters() == null || api.queryParameters().stream().anyMatch(java.util.Objects::isNull)
                || api.queryParameters().size() != expected.size()
                || !Set.copyOf(api.queryParameters()).equals(expected)) throw configuration();
        for (var parameter : parameters.entrySet()) {
            if (!expected.contains(parameter.getKey()) || parameter.getValue().size() != 1) throw invalidQuery();
        }
        int page = (int) parameter(parameters, "page", "0", 0, 1_000_000);
        int size = (int) parameter(parameters, "size", "20", 1, 100);
        if (api.responseType().equals("USER_OPTIONS")) return users(component, api, authorization, page, size);
        long userId = parameter(parameters, "userId", null, 1, Long.MAX_VALUE);
        return transactions(component, api, authorization, userId, page, size);
    }

    private Page<UserOption> users(ComponentDefinition component, ComponentApi api, String authorization, int page, int size) {
        JsonNode root = json(component, api.upstreamPath() + "?page=" + page + "&size=" + size, authorization);
        List<UserOption> items = new ArrayList<>();
        for (JsonNode item : items(root, size)) {
            items.add(new UserOption(identifier(item, "id"), text(item, "username", 64)));
        }
        return page(root, items, page, size);
    }

    private Page<TransactionView> transactions(ComponentDefinition component, ComponentApi api, String authorization, long userId, int page, int size) {
        JsonNode root = json(component, api.upstreamPath() + "?userId=" + userId + "&page=" + page + "&size=" + size, authorization);
        List<TransactionView> items = new ArrayList<>();
        for (JsonNode item : items(root, size)) {
            String itemUserId = identifier(item, "userId");
            if (!itemUserId.equals(Long.toString(userId))) throw invalid();
            items.add(new TransactionView(identifier(item, "id"), itemUserId,
                    text(item, "monthName", 32), number(item, "monthCount", 1, Integer.MAX_VALUE),
                    amount(item), timestamp(item, "createdAt"), timestamp(item, "modifiedAt"),
                    Long.toString(number(item, "version", 0, Long.MAX_VALUE))));
        }
        return page(root, items, page, size);
    }

    public byte[] asset(ComponentDefinition component, String assetName) {
        validate(component, null);
        List<String> allowedTypes = switch (assetName) {
            case "loader.js" -> List.of("application/javascript", "text/javascript");
            case "styles.css" -> List.of("text/css");
            case "manifest.json" -> List.of("application/json");
            default -> throw new UpstreamFailure(404, "NOT_FOUND", "Component resource not found.");
        };
        HttpResponse<byte[]> response = get(component, "/js/components/" + component.resourceName() + "/" + assetName, null);
        String type = contentType(response);
        for (String allowed : allowedTypes) if (type.equals(allowed)) return response.body();
        throw invalid();
    }

    private JsonNode json(ComponentDefinition component, String path, String authorization) {
        HttpResponse<byte[]> response = get(component, path, authorization);
        String type = contentType(response);
        if (!(type.equals("application/json") || type.startsWith("application/") && type.endsWith("+json"))) throw invalid();
        try {
            JsonNode root = mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(response.body());
            if (root == null || !root.isObject()) throw invalid();
            return root;
        } catch (RuntimeException exception) { throw invalid(); }
    }

    private HttpResponse<byte[]> get(ComponentDefinition component, String fixedPath, String authorization) {
        var request = HttpRequest.newBuilder(endpoint(component, fixedPath)).GET().timeout(Duration.ofSeconds(8));
        if (authorization != null) request.header("Authorization", authorization);
        var pending = http.sendAsync(request.build(), ignored -> new BoundedSubscriber());
        try {
            var response = pending.get(9, TimeUnit.SECONDS);
            if (response.statusCode() != 200) throw failure(response.statusCode());
            return response;
        } catch (InterruptedException exception) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (TimeoutException exception) {
            pending.cancel(true);
            throw unavailable();
        } catch (ExecutionException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof OversizedBody) throw invalid();
            }
            throw unavailable();
        }
    }

    private URI endpoint(ComponentDefinition component, String fixedPath) {
        URI configured = UserInfoClientProperties.validateBaseUri(component.baseUrl());
        if (!properties.permits(configured)) throw configuration();
        if (properties.mode().equals("direct")) return URI.create(component.baseUrl().replaceAll("/+$", "") + fixedPath);
        try {
            DiscoveryClient discovery = discoveries.getIfAvailable();
            if (discovery == null) throw unavailable();
            for (var instance : discovery.getInstances(component.serviceId())) {
                if (instance.getHost() == null || instance.getHost().isBlank()
                        || instance.getPort() < 1 || instance.getPort() > 65535) continue;
                URI origin = new URI(instance.isSecure() ? "https" : "http", null, instance.getHost(),
                        instance.getPort(), configured.getPath().replaceAll("/+$", ""), null, null);
                UserInfoClientProperties.validateBaseUri(origin.toString());
                if (!properties.permits(origin)) continue;
                return URI.create(origin + fixedPath);
            }
            throw unavailable();
        } catch (URISyntaxException | RuntimeException exception) { throw unavailable(); }
    }

    public static void validate(ComponentDefinition component, ComponentApi api) {
        if (component == null || !slug(component.componentCode()) || !slug(component.resourceName())
                || component.baseUrl() == null
                || component.serviceId() == null || !component.serviceId().matches("[A-Za-z0-9._-]{1,128}")) throw configuration();
        try {
            URI uri = UserInfoClientProperties.validateBaseUri(component.baseUrl());
            if (uri.getRawPath() != null && !uri.getRawPath().matches("(/[A-Za-z0-9_-]+)*/*")) throw configuration();
        } catch (IllegalArgumentException exception) { throw configuration(); }
        if (api != null && (!component.componentCode().equals(api.componentCode()) || !slug(api.operationCode())
                || api.upstreamPath() == null || !api.upstreamPath().matches("/api/[a-z][a-z0-9-]{0,79}")
                || api.uiActivityCode() == null || !api.uiActivityCode().matches("[A-Z][A-Z0-9_]{0,127}")
                || api.functionalActivityCode() == null || !api.functionalActivityCode().matches("[A-Z][A-Z0-9_]{0,127}"))) throw configuration();
    }

    private static boolean slug(String value) { return value != null && value.matches("[a-z][a-z0-9-]{0,79}"); }

    private static long parameter(MultiValueMap<String, String> values, String name, String fallback, long min, long max) {
        String value = values.getFirst(name);
        if (value == null) value = fallback;
        if (value == null || !value.matches("[0-9]{1,19}")) throw invalidQuery();
        try {
            long parsed = Long.parseLong(value);
            if (parsed < min || parsed > max) throw invalidQuery();
            return parsed;
        } catch (NumberFormatException exception) { throw invalidQuery(); }
    }

    public static UpstreamFailure configuration() {
        return new UpstreamFailure(503, "COMPONENT_CONFIGURATION_ERROR", "Component configuration is unavailable or not permitted.");
    }
    private static UpstreamFailure invalidQuery() {
        return new UpstreamFailure(400, "INVALID_REQUEST", "Use only the allowed filter names and valid pagination values.");
    }

    private static String contentType(HttpResponse<?> response) {
        return response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
    }

    private static Iterable<JsonNode> items(JsonNode root, int size) {
        JsonNode values = root.get("items");
        if (values == null || !values.isArray() || values.size() > size) throw invalid();
        return values;
    }

    private static <T> Page<T> page(JsonNode root, List<T> items, int requestedPage, int requestedSize) {
        long page = number(root, "page", 0, 1_000_000);
        long size = number(root, "size", 1, 100);
        long count = number(root, "totalElements", 0, Long.MAX_VALUE);
        long pages = number(root, "totalPages", 0, Long.MAX_VALUE);
        if (page != requestedPage || size != requestedSize || pages != count / size + (count % size == 0 ? 0 : 1)) throw invalid();
        return new Page<>(List.copyOf(items), (int) page, (int) size, count, pages);
    }

    private static long number(JsonNode object, String key, long min, long max) {
        JsonNode value = object.get(key);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) throw invalid();
        long number = value.longValue();
        if (number < min || number > max) throw invalid();
        return number;
    }

    private static String identifier(JsonNode object, String key) {
        JsonNode value = object.get(key);
        if (value != null && value.isString()) {
            String text = value.stringValue();
            if (!text.matches("[1-9][0-9]{0,18}")) throw invalid();
            try { if (Long.parseLong(text) > 0) return text; } catch (NumberFormatException exception) { throw invalid(); }
            throw invalid();
        }
        return Long.toString(number(object, key, 1, Long.MAX_VALUE));
    }

    private static String text(JsonNode object, String key, int max) {
        JsonNode value = object.get(key);
        if (value == null || !value.isString() || value.stringValue().isBlank() || value.stringValue().length() > max) throw invalid();
        return value.stringValue();
    }

    private static String timestamp(JsonNode object, String key) {
        String value = text(object, key, 64);
        try { OffsetDateTime.parse(value); return value; } catch (RuntimeException exception) { throw invalid(); }
    }

    private static String amount(JsonNode object) {
        JsonNode value = object.get("amount");
        try {
            if (value == null || !(value.isNumber() || value.isString())) throw invalid();
            if (value.isString() && value.stringValue().length() > 64) throw invalid();
            BigDecimal amount = value.isString() ? new BigDecimal(value.stringValue()) : value.decimalValue();
            if (amount.signum() < 0 || amount.scale() < -17 || amount.scale() > 2 || amount.precision() - amount.scale() > 17) throw invalid();
            return amount.toPlainString();
        } catch (RuntimeException exception) { throw invalid(); }
    }

    private static UpstreamFailure failure(int status) {
        return switch (status) {
            case 400 -> new UpstreamFailure(400, "INVALID_REQUEST", "Check the component filters.");
            case 401 -> new UpstreamFailure(401, "SESSION_INVALID", "Please sign in again.");
            case 403 -> new UpstreamFailure(403, "FORBIDDEN", "This activity is not allowed.");
            case 404 -> new UpstreamFailure(404, "NOT_FOUND", "The requested data is not available.");
            case 429, 500, 502, 503, 504 -> unavailable();
            default -> invalid();
        };
    }

    private static UpstreamFailure unavailable() {
        return new UpstreamFailure(503, "COMPONENT_UNAVAILABLE", "The component is unavailable. Try again later.");
    }
    private static UpstreamFailure invalid() {
        return new UpstreamFailure(502, "INVALID_COMPONENT_RESPONSE", "The component returned an unexpected response.");
    }

    public record UserOption(String id, String username) { }
    public record TransactionView(String id, String userId, String monthName, long monthCount,
            String amount, String createdAt, String modifiedAt, String version) { }
    public record Page<T>(List<T> items, int page, int size, long totalElements, long totalPages) { }

    private static final class OversizedBody extends RuntimeException { }
    private static final class BoundedSubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) {
            if (subscription != null) { value.cancel(); return; }
            subscription = value;
            value.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAX_BYTES - bytes.size()) {
                    subscription.cancel(); result.completeExceptionally(new OversizedBody()); return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
