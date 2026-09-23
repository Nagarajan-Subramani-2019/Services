package com.oxygenraj.userui.client;

import com.oxygenraj.userui.api.CreateUserRequest;
import com.oxygenraj.userui.api.SignInRequest;
import com.oxygenraj.userui.api.SignInResponse;
import com.oxygenraj.userui.api.UserView;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class UserInfoClient {
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private final BackendEndpointResolver endpointResolver;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public UserInfoClient(BackendEndpointResolver endpointResolver, ObjectMapper mapper) {
        this.endpointResolver = endpointResolver;
        this.mapper = mapper;
    }

    @PreDestroy
    void closeHttpClient() {
        http.shutdownNow();
    }

    public UserView create(CreateUserRequest input) {
        return user(send("POST", "/userdetails", input, 201));
    }

    public UserView find(long id) {
        return user(send("GET", "/userdetails/" + id, null, 200));
    }

    public SignInResponse signIn(SignInRequest input) {
        if (input.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new UpstreamFailure(400, "VALIDATION_FAILED", "Password must not exceed 72 UTF-8 bytes.");
        }
        JsonNode result = send("POST", "/authuserdetails", input, 200);
        JsonNode authenticated = result.get("authenticated");
        if (authenticated == null || !authenticated.isBoolean() || !authenticated.booleanValue()) {
            throw UpstreamFailure.invalidResponse();
        }
        return new SignInResponse(true, user(result.get("user")));
    }

    private JsonNode send(String method, String path, Object input, int expectedStatus) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpointResolver.resolve(path))
                .timeout(Duration.ofSeconds(8)).header("Accept", "application/json");
        try {
            if (input == null) {
                builder.GET();
            } else {
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(input)));
            }
        } catch (RuntimeException exception) {
            throw UpstreamFailure.invalidResponse();
        }
        CompletableFuture<HttpResponse<byte[]>> pending = http.sendAsync(builder.build(), info -> new LimitedBodySubscriber());
        HttpResponse<byte[]> response;
        try {
            // Covers slow body streams as well as header/connect waits; never keep an unbounded request alive.
            response = pending.get(9, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw UpstreamFailure.unavailable();
        } catch (TimeoutException exception) {
            pending.cancel(true);
            throw UpstreamFailure.unavailable();
        } catch (ExecutionException exception) {
            Throwable cause = exception;
            while (cause != null) {
                if (cause instanceof OversizedResponse) throw UpstreamFailure.invalidResponse();
                cause = cause.getCause();
            }
            throw UpstreamFailure.unavailable();
        }
        if (response.statusCode() != expectedStatus) throw mapFailure(response.statusCode());
        String contentType = response.headers().firstValue("Content-Type").orElse("")
                .split(";", 2)[0].strip().toLowerCase(java.util.Locale.ROOT);
        if (!(contentType.equals("application/json") || contentType.startsWith("application/") && contentType.endsWith("+json"))) {
            throw UpstreamFailure.invalidResponse();
        }
        try {
            JsonNode parsed = mapper.readTree(response.body());
            if (parsed == null || !parsed.isObject()) throw UpstreamFailure.invalidResponse();
            return parsed;
        } catch (RuntimeException exception) {
            throw UpstreamFailure.invalidResponse();
        }
    }

    private static UpstreamFailure mapFailure(int status) {
        return switch (status) {
            case 400 -> new UpstreamFailure(400, "INVALID_REQUEST", "Check the submitted user details.");
            case 401 -> new UpstreamFailure(401, "INVALID_CREDENTIALS", "Invalid username or password.");
            case 403 -> new UpstreamFailure(403, "FORBIDDEN", "This operation is not allowed.");
            case 404 -> new UpstreamFailure(404, "NOT_FOUND", "User was not found.");
            case 409 -> new UpstreamFailure(409, "USER_EXISTS", "A user with that username or email already exists.");
            case 429, 500, 502, 503, 504 -> UpstreamFailure.unavailable();
            default -> UpstreamFailure.invalidResponse();
        };
    }

    private static UserView user(JsonNode object) {
        if (object == null || !object.isObject()) throw UpstreamFailure.invalidResponse();
        JsonNode id = object.get("id");
        JsonNode enabled = object.get("enabled");
        if (id == null || !id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() < 1
                || enabled == null || !enabled.isBoolean()) throw UpstreamFailure.invalidResponse();
        return new UserView(id.longValue(), text(object, "username", 64), text(object, "email", 254),
                text(object, "phoneNumber", 32), text(object, "role", 32), enabled.booleanValue(),
                timestamp(object, "createdAt"), timestamp(object, "modifiedAt"));
    }

    private static String text(JsonNode object, String name, int maxLength) {
        JsonNode value = object.get(name);
        if (value == null || !value.isString() || value.stringValue().isBlank()
                || value.stringValue().length() > maxLength) throw UpstreamFailure.invalidResponse();
        return value.stringValue();
    }

    private static String timestamp(JsonNode object, String name) {
        String value = text(object, name, 64);
        try {
            OffsetDateTime.parse(value);
            return value;
        } catch (DateTimeParseException exception) {
            throw UpstreamFailure.invalidResponse();
        }
    }

    private static final class OversizedResponse extends RuntimeException { }

    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override
        public CompletionStage<byte[]> getBody() { return result; }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            if (subscription != null) { value.cancel(); return; }
            subscription = value;
            value.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAX_RESPONSE_BYTES - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new OversizedResponse());
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable throwable) { result.completeExceptionally(throwable); }

        @Override
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
