package io.smartup.zipkin;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import zipkin2.Span;

import java.util.Map;
import java.util.concurrent.TimeUnit;

public class DDMappingSpan {

    private final Span delegateSpan;

    DDMappingSpan(Span delegateSpan) {
        this.delegateSpan = delegateSpan;
    }

    /**
     * Parses a 1 to 32 character lower-hex string with no prefix into an unsigned long, tossing any
     * bits higher than 64.
     */
    public static long lowerHexToUnsignedLong(String lowerHex) {
        int length = lowerHex.length();
        if (length < 1 || length > 32) throw isntLowerHexLong(lowerHex);

        // trim off any high bits
        int beginIndex = length > 16 ? length - 16 : 0;

        return lowerHexToUnsignedLong(lowerHex, beginIndex);
    }

    /**
     * Parses a 16 character lower-hex string with no prefix into an unsigned long, starting at the
     * spe index.
     */
    public static long lowerHexToUnsignedLong(String lowerHex, int index) {
        long result = 0;
        for (int endIndex = Math.min(index + 16, lowerHex.length()); index < endIndex; index++) {
            char c = lowerHex.charAt(index);
            result <<= 4;
            if (c >= '0' && c <= '9') {
                result |= c - '0';
            } else if (c >= 'a' && c <= 'f') {
                result |= c - 'a' + 10;
            } else {
                throw isntLowerHexLong(lowerHex);
            }
        }
        return result;
    }

    static NumberFormatException isntLowerHexLong(String lowerHex) {
        throw new NumberFormatException(
                lowerHex + " should be a 1 to 32 character lower-hex string with no prefix");
    }

    @JsonGetter("start")
    public long getStartTime() {
        return TimeUnit.MICROSECONDS.toNanos(delegateSpan.timestampAsLong());
    }

    @JsonGetter("duration")
    public long getDurationNano() {
        long duration = TimeUnit.MICROSECONDS.toNanos(delegateSpan.durationAsLong());

        return duration == 0 ? 1 : duration;
    }

    @JsonGetter("service")
    public String getServiceName() {
        return delegateSpan.localServiceName();
    }

    /**
     * This method only returns the lower 64 bits of the trace id, so that is all that will be sent to Datadog.
     *
     * @return
     */
    @JsonGetter("trace_id")
    public long getTraceId() {
        return lowerHexToUnsignedLong(delegateSpan.traceId());
    }

    /**
     * This method only returns the lower 64 bits of the span id, so that is all that will be sent to Datadog.
     *
     * @return
     */
    @JsonGetter("span_id")
    public long getSpanId() {
        return lowerHexToUnsignedLong(delegateSpan.id());
    }

    @JsonGetter("parent_id")
    public long getParentId() {
        if (delegateSpan.parentId() == null) {
            return 0;
        } else {
            return lowerHexToUnsignedLong(delegateSpan.parentId());
        }
    }

    @JsonGetter("resource")
    public String getResourceName() {
        Map<String, String> tags = delegateSpan.tags();
        if (tags.containsKey("http.route")) {
            return tags.get("http.route");
        }
        if (tags.containsKey("sql.query")) {
            return tags.get("sql.query");
        }
        if (tags.containsKey("cassandra.query")) {
            return tags.get("cassandra.query");
        }
        if (tags.containsKey("db.statement")) {
            // Using Opentracing?
            return tags.get("db.statement");
        }
        return delegateSpan.name();
    }

    @JsonGetter("name")
    public String getOperationName() {
        // DataDog does not support trace names without alphanumerical characters

        return getType() + " " + delegateSpan.name();
    }

    @JsonGetter("sampling_priority")
    @JsonInclude(Include.NON_NULL)
    public Integer getSamplingPriority() {
        return Boolean.TRUE.equals(delegateSpan.debug()) ? 1 : 0;
    }

    @JsonGetter
    public Map<String, String> getMeta() {
        return delegateSpan.tags();
    }

    @JsonGetter
    public String getType() {
        if (delegateSpan.kind() == null) {
            return "other";
        }
        switch (delegateSpan.kind()) {
            case CONSUMER:
            case PRODUCER:
                return "queue";
            case CLIENT:
                if (delegateSpan.tags().containsKey("sql.query")) {
                    return "sql";
                }
                if (delegateSpan.tags().containsKey("cassandra.query")) {
                    // brave-cassandra
                    return "cassandra";
                }
                if (delegateSpan.tags().containsKey("http.path")
                        || delegateSpan.tags().containsKey("http.uri")) {
                    return "http";
                }
                break;
            case SERVER:
                if (delegateSpan.tags().containsKey("http.path")
                        || delegateSpan.tags().containsKey("http.uri")) {
                    return "web";
                }
        }
        return null;
    }

    @JsonGetter
    public int getError() {
        return delegateSpan.tags().containsKey("error") ? 1 : 0;
    }

    @Override
    public String toString() {
        return "DDMappingSpan={" +
          "startTime=" + getStartTime() +
          ", durationNano=" + getDurationNano() +
          ", serviceName=" + getServiceName() +
          ", traceId=" + getTraceId() +
          ", spanId=" + getSpanId() +
          ", parentId=" + getParentId() +
          ", resourceName='" + getResourceName() + "\'" +
          ", operationName='" + getOperationName() + "\'" +
          ", samplingPriority=" + getSamplingPriority() +
          ", meta=" + getMeta() +
          ", type=" + getType() +
          ", delegateSpan=" + delegateSpan.toString();
    }
}
