package io.smartup.zipkin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.msgpack.jackson.dataformat.MessagePackFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The API pointing to a DD agent
 */
class DDApi {
    static final String DEFAULT_HOSTNAME = "localhost";
    static final int DEFAULT_PORT = 8126;
    static final String JAVA_VERSION = System.getProperty("java.version", "unknown");
    static final String JAVA_VM_NAME = System.getProperty("java.vm.name", "unknown");
    private static final Logger log = LoggerFactory.getLogger(DDApi.class);
    private static final String DATADOG_META_LANG = "Datadog-Meta-Lang";
    private static final String DATADOG_META_LANG_VERSION = "Datadog-Meta-Lang-Version";
    private static final String DATADOG_META_LANG_INTERPRETER = "Datadog-Meta-Lang-Interpreter";
    private static final String DATADOG_META_TRACER_VERSION = "Datadog-Meta-Tracer-Version";
    private static final String X_DATADOG_TRACE_COUNT = "X-Datadog-Trace-Count";

    private static final String TRACES_ENDPOINT_V3 = "/v0.3/traces";
    private static final String TRACES_ENDPOINT_V4 = "/v0.4/traces";
    private static final long MILLISECONDS_BETWEEN_ERROR_LOG = TimeUnit.MINUTES.toMillis(5);
    private static final ObjectMapper objectMapper = new ObjectMapper(new MessagePackFactory());
    private final String tracesEndpoint;
    private volatile long nextAllowedLogTime = 0;

    DDApi(final String host, final int port) {
        this(host, port, traceEndpointAvailable("http://" + host + ":" + port + TRACES_ENDPOINT_V4));
    }

    DDApi(final String host, final int port, final boolean v4EndpointsAvailable) {
        if (v4EndpointsAvailable) {
            this.tracesEndpoint = "http://" + host + ":" + port + TRACES_ENDPOINT_V4;
        } else {
            log.trace("API v0.4 endpoints not available. Downgrading to v0.3");
            this.tracesEndpoint = "http://" + host + ":" + port + TRACES_ENDPOINT_V3;
        }
    }

    private static boolean traceEndpointAvailable(final String endpoint) {
        return endpointAvailable(endpoint, Collections.emptyList(), true);
    }

    private static boolean endpointAvailable(
            final String endpoint, final Object data, final boolean retry) {
        try {
            final HttpURLConnection httpCon = getHttpURLConnection(endpoint);

            // This is potentially called in premain, so we want to fail fast.
            httpCon.setConnectTimeout((int) TimeUnit.SECONDS.toMillis(1));
            httpCon.setReadTimeout((int) TimeUnit.SECONDS.toMillis(1));

            try (final OutputStream out = httpCon.getOutputStream()) {
                objectMapper.writeValue(out, data);
                out.flush();
            }
            return httpCon.getResponseCode() == 200;
        } catch (final IOException e) {
            if (retry) {
                return endpointAvailable(endpoint, data, false);
            }
        }
        return false;
    }

    private static HttpURLConnection getHttpURLConnection(final String endpoint) throws IOException {
        final HttpURLConnection httpCon;
        final URL url = new URL(endpoint);
        httpCon = (HttpURLConnection) url.openConnection();
        httpCon.setDoOutput(true);
        httpCon.setDoInput(true);
        httpCon.setRequestMethod("PUT");
        httpCon.setRequestProperty("Content-Type", "application/msgpack");
        httpCon.setRequestProperty(DATADOG_META_LANG, "java");
        httpCon.setRequestProperty(DATADOG_META_LANG_VERSION, JAVA_VERSION);
        httpCon.setRequestProperty(DATADOG_META_LANG_INTERPRETER, JAVA_VM_NAME);
        httpCon.setRequestProperty(DATADOG_META_TRACER_VERSION, "zipkin-reporter");

        return httpCon;
    }

    /**
     * Send traces to the DD agent
     *
     * @param traces the traces to be sent
     * @return the staus code returned
     */
    void sendTraces(final List<List<DDMappingSpan>> traces) {
        if (log.isTraceEnabled()) {
            log.trace("Sending traces {}", traces);
        }
        final int totalSize = traces.size();
        try {
            final HttpURLConnection httpCon = getHttpURLConnection(tracesEndpoint);
            httpCon.setRequestProperty(X_DATADOG_TRACE_COUNT, String.valueOf(totalSize));

            try (final OutputStream out = httpCon.getOutputStream()) {
                objectMapper.writeValue(out, traces);
                out.flush();
            }

            String responseString = null;
            {
                final BufferedReader responseReader =
                        new BufferedReader(
                                new InputStreamReader(httpCon.getInputStream(), StandardCharsets.UTF_8));
                final StringBuilder sb = new StringBuilder();

                String line = null;
                while ((line = responseReader.readLine()) != null) {
                    sb.append(line);
                }
                skipAllContent(httpCon);
                responseReader.close();

                responseString = sb.toString();
            }

            final int responseCode = httpCon.getResponseCode();
            if (responseCode != 200) {
                if (log.isTraceEnabled()) {
                    log.trace("Error while sending {} of {} traces to the DD agent. Status: {}, ResponseMessage: ",
                            traces.size(),
                            totalSize,
                            responseCode,
                            httpCon.getResponseMessage());
                } else if (nextAllowedLogTime < System.currentTimeMillis()) {
                    nextAllowedLogTime = System.currentTimeMillis() + MILLISECONDS_BETWEEN_ERROR_LOG;
                    log.warn("Error while sending {} of {} traces to the DD agent. Status: {} (going silent for {} seconds)",
                            traces.size(),
                            totalSize,
                            responseCode,
                            httpCon.getResponseMessage(),
                            TimeUnit.MILLISECONDS.toMinutes(MILLISECONDS_BETWEEN_ERROR_LOG));
                }
                return;
            }

            log.trace("Succesfully sent {} of {} traces to the DD agent.", traces.size(), totalSize);

            try {
                if (null != responseString
                        && !"".equals(responseString.trim())
                        && !"OK".equalsIgnoreCase(responseString.trim())) {
                    final JsonNode response = objectMapper.readTree(responseString);
                }
            } catch (final IOException e) {
                log.warn("Failed to parse DD agent response: " + responseString, e);
            }

        } catch (final IOException e) {
            if (log.isWarnEnabled()) {
                log.trace("Error while sending {} of {} traces to the DD agent.", traces.size(), totalSize, e);
            } else if (nextAllowedLogTime < System.currentTimeMillis()) {
                nextAllowedLogTime = System.currentTimeMillis() + MILLISECONDS_BETWEEN_ERROR_LOG;
                log.warn("Error while sending {} of {} traces to the DD agent. {}: {} (going silent for {} minutes)",
                                traces.size(),
                                totalSize,
                                e.getClass().getName(),
                                e.getMessage(),
                                TimeUnit.MILLISECONDS.toMinutes(MILLISECONDS_BETWEEN_ERROR_LOG));
            }
        }
    }

    /* Ensure we read the full response. Borrowed from https://github.com/openzipkin/zipkin-reporter-java/blob/2eb169e/urlconnection/src/main/java/zipkin2/reporter/urlconnection/URLConnectionSender.java#L231-L252 */
    private void skipAllContent(HttpURLConnection connection) throws IOException {
        InputStream in = connection.getInputStream();
        IOException thrown = skipAndSuppress(in);
        if (thrown == null) return;
        InputStream err = connection.getErrorStream();
        if (err != null) skipAndSuppress(err); // null is possible, if the connection was dropped
        throw thrown;
    }

    private IOException skipAndSuppress(InputStream in) {
        try {
            while (in.read() != -1) ; // skip
            return null;
        } catch (IOException e) {
            return e;
        } finally {
            try {
                in.close();
            } catch (IOException suppressed) {
            }
        }
    }

    @Override
    public String toString() {
        return "DDApi { tracesEndpoint=" + tracesEndpoint + " }";
    }
}
