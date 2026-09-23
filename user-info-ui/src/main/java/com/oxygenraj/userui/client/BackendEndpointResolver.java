package com.oxygenraj.userui.client;

import com.oxygenraj.userui.config.UserInfoClientProperties;
import java.net.URI;
import java.net.URISyntaxException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.stereotype.Component;

@Component
public class BackendEndpointResolver {
    private final UserInfoClientProperties properties;
    private final ObjectProvider<DiscoveryClient> discoveryClients;

    public BackendEndpointResolver(UserInfoClientProperties properties, ObjectProvider<DiscoveryClient> discoveryClients) {
        this.properties = properties;
        this.discoveryClients = discoveryClients;
    }

    public URI resolve(String fixedOperationPath) {
        if (properties.mode().equals("direct")) {
            return URI.create(properties.baseUrl() + fixedOperationPath);
        }
        try {
            DiscoveryClient discovery = discoveryClients.getIfAvailable();
            if (discovery == null) throw UpstreamFailure.unavailable();
            for (ServiceInstance instance : discovery.getInstances(properties.serviceId())) {
                if (instance.getHost() == null || instance.getHost().isBlank()
                        || instance.getPort() < 1 || instance.getPort() > 65535) continue;
                URI candidate = new URI(instance.isSecure() ? "https" : "http", null,
                        instance.getHost(), instance.getPort(), properties.contextPath() + fixedOperationPath, null, null);
                UserInfoClientProperties.validateBaseUri(candidate.toString());
                return candidate;
            }
            throw UpstreamFailure.unavailable();
        } catch (URISyntaxException | IllegalArgumentException exception) {
            throw UpstreamFailure.unavailable();
        } catch (RuntimeException exception) {
            if (exception instanceof UpstreamFailure failure) throw failure;
            throw UpstreamFailure.unavailable();
        }
    }
}
