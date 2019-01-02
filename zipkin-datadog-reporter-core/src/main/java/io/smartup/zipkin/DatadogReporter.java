package io.smartup.zipkin;

import zipkin2.Span;
import zipkin2.reporter.Reporter;

import java.io.Closeable;
import java.io.Flushable;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Collect spans from Zipkin and group them into traces so they can be reported together. Traces are
 * reported to the agent after a span is reported that appears to be the root, or after a
 * TIMEOUT_DELAY period. A span is assumed to be a root if it has a Span.Kind of either SERVER or
 * CONSUMER.
 *
 * <p>This implementation groups spans into traces using an unbounded ConcurrentHashMap.
 * "Incomplete" traces are flushed after 30 seconds, but only 1 second if a root span is reported.
 * This means that spikes of traffic might cause unbounded growth of the contained
 * ConcurrentHashMap.
 */
public class DatadogReporter implements Reporter<Span>, Flushable, Closeable {
    public static final long TIMEOUT_DELAY = TimeUnit.SECONDS.toNanos(30);
    public static final long COMPLETION_DELAY = TimeUnit.SECONDS.toNanos(1);

    private final Queue<List<DDMappingSpan>> reportingTraces;
    private final long messageTimeout;
    private final Map<String, PendingTrace> pendingTraces = new ConcurrentHashMap<>();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final DDApi ddApi;
    private volatile Thread flushingThread;

    /**
     * Report traces to the configured Datadog Agent.
     *
     * @param host (See DDApi.DEFAULT_HOSTNAME)
     * @param port (See DDApi.DEFAULT_PORT)
     */
    public DatadogReporter(String host, int port, int messageTimeout) {
        this(host, port, messageTimeout, new LinkedBlockingQueue<>());
    }

    /**
     * Intended for testing. Doesn't actually report to the agent.
     *
     * @param reportedTraces - queue where traces will be sent to.
     */
    public DatadogReporter(Queue<List<DDMappingSpan>> reportedTraces) {
        this.reportingTraces = reportedTraces;
        this.ddApi = null;
        this.messageTimeout = TimeUnit.SECONDS.toNanos(1);
    }

    private DatadogReporter(String host, int port, int messageTimeout, Queue<List<DDMappingSpan>> reportingTraces) {
        this.reportingTraces = reportingTraces;
        this.messageTimeout = TimeUnit.SECONDS.toNanos(messageTimeout);
        ddApi = new DDApi(host, port);
    }

    @Override
    public void report(Span span) {
        if (flushingThread == null) {
            synchronized (this) {
                if (flushingThread == null) {
                    running.set(true);
                    flushingThread = new Thread(() -> flushPeriodically(), "zipkin-datadog-flusher");
                    flushingThread.setDaemon(true);
                    flushingThread.start();
                }
            }
        }

        PendingTrace trace = new PendingTrace();
        PendingTrace previousTrace = pendingTraces.putIfAbsent(span.traceId(), trace);
        trace = previousTrace != null ? previousTrace : trace; // Handles race condition

        trace.spans.add(new DDMappingSpan(span));

        /* If the span kind is server or consumer, we assume it is the root of the trace.
         * That implies all span children have likely already been reported and can be
         * flushed in the next cycle, though in some async cases, this might not be the case.
         */
        if (span.kind() == Span.Kind.SERVER
                || span.kind() == Span.Kind.CONSUMER
                || span.parentId() == null) {
            trace.expiration = nanoTime() + COMPLETION_DELAY;
        } else {
            trace.expiration = nanoTime() + TIMEOUT_DELAY;
        }
    }

    @Override
    public void flush() {
        long currentTime = nanoTime();
        Iterator<Map.Entry<String, PendingTrace>> iterator = pendingTraces.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, PendingTrace> next = iterator.next();
            if (currentTime > next.getValue().expiration) {
                reportingTraces.add(next.getValue().spans);
                iterator.remove();
            }
        }
        if (!reportingTraces.isEmpty()) {
            sendTraces();
        }
    }

    private void sendTraces() {
        if (ddApi == null) {
            return;
        }
        List<List<DDMappingSpan>> traces = new ArrayList<>(reportingTraces.size());
        List<DDMappingSpan> trace = reportingTraces.poll();
        while (trace != null) {
            traces.add(trace);
            trace = reportingTraces.poll();
        }
        ddApi.sendTraces(traces);
    }

    private void flushPeriodically() {
        while (running.get()) {
            try {
                flush();
                Thread.sleep(messageTimeout);
            } catch (InterruptedException e) {
            }
        }
    }

    @Override
    public void close() {
        if (flushingThread == null) {
            return;
        }

        running.set(false);

        flushingThread.interrupt();
        try {
            flushingThread.join();
            flushingThread = null;
        } catch (InterruptedException e) {
        }
    }

    protected long nanoTime() {
        return System.nanoTime();
    }

    private class PendingTrace {
        public final List<DDMappingSpan> spans = new CopyOnWriteArrayList<>();
        public volatile long expiration = nanoTime() + TIMEOUT_DELAY;
    }
}
