package com.oxygenraj.transactionui;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class JsonGatewayTest {
    HttpServer server;
    JsonGateway gateway;
    BackendProperties.Endpoint endpoint;
    int status;
    String body,type;
    @BeforeEach void setup() throws Exception {
        status=200; body="{}"; type="application/json";
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            exchange.getRequestBody().readAllBytes();
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type",type);
            if(status==302) exchange.getResponseHeaders().set("Location","http://127.0.0.1:1/never-follow");
            exchange.sendResponseHeaders(status,bytes.length);
            try(var out=exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        endpoint=new BackendProperties.Endpoint("direct","http://127.0.0.1:"+server.getAddress().getPort()+"/service","service","/service");
        gateway=new JsonGateway(new ObjectMapper(),new DefaultListableBeanFactory().getBeanProvider(DiscoveryClient.class));
    }
    @AfterEach void close() { gateway.close(); server.stop(0); }
    @Test void preservesExactDecimalFromRealHttpResponse() {
        body="{\"amount\":99999999999999999.99}";
        assertThat(gateway.request(endpoint,"/test",null).path("amount").decimalValue().toPlainString()).isEqualTo("99999999999999999.99");
    }
    @ParameterizedTest @ValueSource(ints={302,401,403,404,500,503})
    void rejectsErrorsAndRedirectsWithoutLeakingBody(int responseStatus) {
        status=responseStatus; body="password=secret ORA-00942";
        assertThatThrownBy(()->gateway.request(endpoint,"/test",null)).isInstanceOf(ApiFailure.class).hasMessageNotContaining("secret");
    }
    @ParameterizedTest @ValueSource(strings={"not-json","[]","{} {}","null"})
    void rejectsMalformedResponses(String responseBody) {
        body=responseBody;
        assertThatThrownBy(()->gateway.request(endpoint,"/test",null)).isInstanceOf(ApiFailure.class);
    }
    @Test void rejectsHtml() { type="text/html"; assertThatThrownBy(()->gateway.request(endpoint,"/test",null)).isInstanceOf(ApiFailure.class); }
    @Test void limitsBodySize() { body="x".repeat(600*1024); assertThatThrownBy(()->gateway.request(endpoint,"/test",null)).isInstanceOf(ApiFailure.class); }
}
