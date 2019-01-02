package io.smartup.zipkin;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("spring.zipkin.datadog")
public class DatadogProperties {

    /**
     * Host of the Datadog agent
     */
    private String host = "localhost";

    /**
     * Port the Datadog agent is listening on
     */
    private int port = 8126;

    /**
     * Enables sending spans to Datadog
     */
    private boolean enabled = true;

    /**
     * Timeout in seconds before pending spans will be sent in batches to Zipkin
     */
    private int messageTimeout = 1;

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getMessageTimeout() {
        return messageTimeout;
    }
}
