package poc.uiagent;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Sends OTLP/JSON over HTTP from one daemon thread. Each signal has its own bounded queue of
 * pre-serialized items (a span, a log record, a metric). Batches go every second or when a flush is
 * asked for; failures back off up to 30 s; a full queue drops the oldest items rather than block the
 * event thread.
 */
public final class Exporter implements Runnable {
    private static final int QUEUE = 5000, BATCH = 500;
    private final Config cfg;
    private final String resourceJson;
    private final LinkedBlockingQueue<String> spans = new LinkedBlockingQueue<String>(QUEUE);
    private final LinkedBlockingQueue<String> logs = new LinkedBlockingQueue<String>(QUEUE);
    private final LinkedBlockingQueue<String> metrics = new LinkedBlockingQueue<String>(QUEUE);
    private final Object wake = new Object();
    private volatile boolean flushNow, stopped;
    private long backoffUntil;
    private int backoffMs = 1000;
    volatile long sent, failed, dropped;
    /** Runs at shutdown before the queues drain, so a last presence row is not left unsent. */
    Runnable beforeShutdown;

    private final Thread shutdownHook = new Thread("ui-agent-shutdown") {
        public void run() { shutdown(); }
    };

    public Exporter(Config cfg, String resourceJson) {
        this.cfg = cfg;
        this.resourceJson = resourceJson;
        Thread t = new Thread(this, "ui-agent-exporter");
        t.setDaemon(true);
        t.start();
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    public void span(String json) { offer(spans, json); }
    public void log(String json) { offer(logs, json); }
    public void metric(String json) { offer(metrics, json); }

    private void offer(LinkedBlockingQueue<String> q, String json) {
        if (stopped) return;
        while (!q.offer(json)) {
            if (q.poll() != null) dropped++;
        }
    }

    public void flush() {
        flushNow = true;
        synchronized (wake) { wake.notifyAll(); }
    }

    public void run() {
        while (!stopped) {
            try {
                synchronized (wake) {
                    if (!flushNow) wake.wait(1000);
                }
                flushNow = false;
                if (System.currentTimeMillis() < backoffUntil) continue;
                boolean ok = drain(spans, "/v1/traces", "resourceSpans", "scopeSpans", "spans")
                        & drain(logs, "/v1/logs", "resourceLogs", "scopeLogs", "logRecords")
                        & drain(metrics, "/v1/metrics", "resourceMetrics", "scopeMetrics", "metrics");
                if (ok) backoffMs = 1000;
                else {
                    backoffUntil = System.currentTimeMillis() + backoffMs;
                    backoffMs = Math.min(backoffMs * 2, 30000);
                }
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                Agent.debug("exporter: " + t);
            }
        }
    }

    private boolean drain(LinkedBlockingQueue<String> q, String path, String resKey, String scopeKey, String itemKey) {
        boolean ok = true;
        while (!q.isEmpty() && ok) {
            List<String> batch = new ArrayList<String>(BATCH);
            q.drainTo(batch, BATCH);
            if (batch.isEmpty()) break;
            StringBuilder b = new StringBuilder(batch.size() * 256);
            b.append("{\"").append(resKey).append("\":[{\"resource\":{\"attributes\":").append(resourceJson)
             .append("},\"").append(scopeKey).append("\":[{\"scope\":{\"name\":\"poc.ui-agent\",\"version\":\"").append(Agent.VERSION)
             .append("\"},\"").append(itemKey).append("\":[");
            for (int i = 0; i < batch.size(); i++) {
                if (i > 0) b.append(',');
                b.append(batch.get(i));
            }
            b.append("]}]}]}");
            ok = post(path, b.toString());
            if (!ok) {
                // Put the batch back at the front as best we can; order within a second doesn't matter.
                for (String s : batch) if (!q.offer(s)) dropped++;
            }
        }
        return ok;
    }

    private boolean post(String path, String body) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(cfg.endpoint + path).openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(10000);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("User-Agent", "poc-ui-agent/" + Agent.VERSION);
            byte[] bytes = body.getBytes("UTF-8");
            c.setFixedLengthStreamingMode(bytes.length);
            OutputStream out = c.getOutputStream();
            out.write(bytes);
            out.close();
            int code = c.getResponseCode();
            if (code >= 200 && code < 300) {
                sent++;
                slurp(c.getInputStream());
                return true;
            }
            failed++;
            Agent.debug("POST " + path + " -> " + code + " " + slurp(c.getErrorStream()));
            // 4xx means this batch will never be accepted; don't retry it forever.
            return code >= 400 && code < 500 && code != 429;
        } catch (Exception e) {
            failed++;
            Agent.debug("POST " + path + " failed: " + e);
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String slurp(InputStream in) {
        if (in == null) return "";
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < 4096) out.write(buf, 0, n);
            in.close();
            return out.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /** Agent stop: send what is queued now and end the thread, instead of at JVM exit. */
    void stop() {
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException alreadyExiting) {
            return;
        }
        shutdown();
        flush();
    }

    void shutdown() {
        try {
            if (beforeShutdown != null) beforeShutdown.run();
        } catch (Throwable ignored) {
        }
        stopped = true;
        try {
            drain(spans, "/v1/traces", "resourceSpans", "scopeSpans", "spans");
            drain(logs, "/v1/logs", "resourceLogs", "scopeLogs", "logRecords");
            drain(metrics, "/v1/metrics", "resourceMetrics", "scopeMetrics", "metrics");
        } catch (Throwable ignored) {
        }
    }
}
