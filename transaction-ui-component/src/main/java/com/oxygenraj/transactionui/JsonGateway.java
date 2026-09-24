package com.oxygenraj.transactionui;

import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.DeserializationFeature;

@Component
public class JsonGateway {
    private final ObjectMapper mapper;
    private final ObjectProvider<DiscoveryClient> discovery;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    public JsonGateway(ObjectMapper mapper,ObjectProvider<DiscoveryClient> discovery) { this.mapper=mapper; this.discovery=discovery; }
    @PreDestroy void close() { http.shutdownNow(); }
    URI resolve(BackendProperties.Endpoint endpoint,String pathAndQuery) {
        if (endpoint.mode().equals("direct")) return URI.create(endpoint.baseUrl()+pathAndQuery);
        try {
            var client=discovery.getIfAvailable();
            if (client == null) throw ApiFailure.unavailable();
            for (var instance:client.getInstances(endpoint.serviceId())) {
                if (instance.getPort()<1 || instance.getPort()>65535) continue;
                var base=new URI(instance.isSecure()?"https":"http",null,instance.getHost(),instance.getPort(),endpoint.contextPath(),null,null);
                BackendProperties.validateUri(base);
                return URI.create(base+pathAndQuery);
            }
        } catch (Exception ignored) { throw ApiFailure.unavailable(); }
        throw ApiFailure.unavailable();
    }
    public JsonNode request(BackendProperties.Endpoint endpoint,String path,Object body) {
        var builder=HttpRequest.newBuilder(resolve(endpoint,path)).timeout(Duration.ofSeconds(8)).header("Accept","application/json");
        if (body==null) builder.GET();
        else builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body)));
        var pending=http.sendAsync(builder.build(),info->new LimitedBody());
        HttpResponse<byte[]> response;
        try { response=pending.get(9,TimeUnit.SECONDS); }
        catch (InterruptedException e) { pending.cancel(true); Thread.currentThread().interrupt(); throw ApiFailure.unavailable(); }
        catch (ExecutionException|TimeoutException e) { pending.cancel(true); throw ApiFailure.unavailable(); }
        if (response.statusCode()!=200) {
            throw switch(response.statusCode()) {
                case 401 -> new ApiFailure(401,"INVALID_CREDENTIALS","Valid credentials are required.");
                case 403 -> new ApiFailure(403,"FORBIDDEN","This operation is not allowed.");
                case 404 -> new ApiFailure(404,"NOT_FOUND","The requested user was not found.");
                case 400 -> new ApiFailure(400,"INVALID_REQUEST","Check the request fields.");
                default -> ApiFailure.unavailable();
            };
        }
        String type=response.headers().firstValue("Content-Type").orElse("").split(";",2)[0].strip();
        if (!type.equalsIgnoreCase("application/json")) throw ApiFailure.invalid();
        try {
            JsonNode node=mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(response.body());
            if(node==null || !node.isObject()) throw ApiFailure.invalid();
            return node;
        } catch(RuntimeException e) { throw ApiFailure.invalid(); }
    }
    private static class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> future=new CompletableFuture<>();
        private final ByteArrayOutputStream out=new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return future; }
        public void onSubscribe(Flow.Subscription s) { subscription=s; s.request(1); }
        public void onNext(List<ByteBuffer> list) {
            for(var b:list) {
                if(b.remaining()>512*1024-out.size()) { subscription.cancel(); future.completeExceptionally(ApiFailure.invalid()); return; }
                byte[] bytes=new byte[b.remaining()]; b.get(bytes); out.writeBytes(bytes);
            }
            subscription.request(1);
        }
        public void onError(Throwable t) { future.completeExceptionally(t); }
        public void onComplete() { future.complete(out.toByteArray()); }
    }
}
