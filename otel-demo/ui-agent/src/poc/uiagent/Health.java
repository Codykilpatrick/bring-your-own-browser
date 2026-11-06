package poc.uiagent;

import java.lang.management.ClassLoadingMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.util.Map;

/**
 * JVM health every N seconds from the platform MXBeans, plus a watchdog that pings the Swing and
 * JavaFX event threads once a second. A ping that takes longer than freeze_ms is a freeze: a
 * java_logs row with the event thread's stack, and a count on ui.event_thread.freezes.
 */
public final class Health implements Runnable {
    private final Telemetry t;
    private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    private final ClassLoadingMXBean classes = ManagementFactory.getClassLoadingMXBean();
    private final Pinger swing = new Pinger("swing", "AWT-EventQueue");
    private final Pinger fx = new Pinger("javafx", "JavaFX Application Thread");
    private long lastCpuNanos = -1, lastCpuWall;

    public Health(Telemetry t) { this.t = t; }

    public void install() {
        Thread th = new Thread(this, "ui-agent-health");
        th.setDaemon(true);
        th.start();
    }

    public void run() {
        long lastReport = 0;
        while (true) {
            try {
                Thread.sleep(1000);
                long now = System.currentTimeMillis();
                pingSwing();
                pingFx();
                swing.check(now);
                fx.check(now);
                Errors.wrapDefault(t);
                if (now - lastReport >= t.cfg.healthSeconds * 1000L) {
                    lastReport = now;
                    report();
                }
            } catch (InterruptedException e) {
                return;
            } catch (Throwable e) {
                Agent.debug("health: " + e);
            }
        }
    }

    private void pingSwing() {
        // Only ping once the app has an event thread; starting one in a headless process is not our call.
        if (java.awt.Window.getWindows().length == 0 && !swing.everSeen) return;
        swing.everSeen = true;
        if (!swing.begin()) return;
        java.awt.EventQueue.invokeLater(swing.done);
    }

    private void pingFx() {
        if (!FxHook.isRunning()) return;
        if (!fx.begin()) return;
        if (!FxHook.runLater(fx.done)) fx.cancel();
    }

    private void report() {
        Map<String, Object> heap = Json.map();
        heap.put("jvm.memory.type", "heap");
        MemoryUsage h = memory.getHeapMemoryUsage();
        t.gauge("jvm.memory.used", "By", h.getUsed(), heap);
        t.gauge("jvm.memory.committed", "By", h.getCommitted(), heap);
        if (h.getMax() > 0) t.gauge("jvm.memory.limit", "By", h.getMax(), heap);
        Map<String, Object> non = Json.map();
        non.put("jvm.memory.type", "non_heap");
        MemoryUsage n = memory.getNonHeapMemoryUsage();
        t.gauge("jvm.memory.used", "By", n.getUsed(), non);
        t.gauge("jvm.memory.committed", "By", n.getCommitted(), non);
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
            Map<String, Object> a = Json.map();
            a.put("jvm.memory.pool.name", p.getName());
            a.put("jvm.memory.type", p.getType() == MemoryType.HEAP ? "heap" : "non_heap");
            MemoryUsage u = p.getUsage();
            if (u != null) t.gauge("jvm.memory.pool.used", "By", u.getUsed(), a);
        }
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            Map<String, Object> a = Json.map();
            a.put("jvm.gc.name", gc.getName());
            if (gc.getCollectionCount() >= 0) t.counter("jvm.gc.collections", "{collection}", gc.getCollectionCount(), a);
            if (gc.getCollectionTime() >= 0) t.counter("jvm.gc.time", "ms", gc.getCollectionTime(), a);
        }
        t.gauge("jvm.thread.count", "{thread}", threads.getThreadCount(), null);
        t.gauge("jvm.class.count", "{class}", classes.getLoadedClassCount(), null);
        t.gauge("process.uptime", "s", ManagementFactory.getRuntimeMXBean().getUptime() / 1000.0, null);
        cpu();
        swing.report();
        fx.report();
        Map<String, Object> ex = Json.map();
        ex.put("result", "sent");
        t.counter("ui_agent.export.batches", "{batch}", t.exporter.sent, ex);
        Map<String, Object> exf = Json.map();
        exf.put("result", "failed");
        t.counter("ui_agent.export.batches", "{batch}", t.exporter.failed, exf);
        t.counter("ui_agent.export.dropped", "{item}", t.exporter.dropped, null);
        t.exporter.flush();
    }

    /** Process CPU as a 0..1 fraction of one core, from com.sun.management when the JVM has it. */
    private void cpu() {
        Object os = ManagementFactory.getOperatingSystemMXBean();
        Object v = Reflect.call(os, "getProcessCpuTime");
        if (!(v instanceof Long)) return;
        long nanos = ((Long) v).longValue();
        long wall = System.nanoTime();
        if (lastCpuNanos >= 0 && wall > lastCpuWall) {
            double frac = (nanos - lastCpuNanos) / (double) (wall - lastCpuWall);
            t.gauge("process.cpu.utilization", "1", Math.max(0, frac), null);
        }
        lastCpuNanos = nanos;
        lastCpuWall = wall;
    }

    /** One event thread's ping state. */
    final class Pinger {
        final String toolkit, threadPrefix;
        volatile boolean everSeen;
        volatile long sentAt;          // 0 when no ping is outstanding
        volatile boolean frozen;
        volatile long lastLatencyMs = -1, maxLatencyMs;
        long freezes;
        final Runnable done = new Runnable() {
            public void run() {
                long started = sentAt;
                if (started == 0) return;
                long ms = System.currentTimeMillis() - started;
                lastLatencyMs = ms;
                if (ms > maxLatencyMs) maxLatencyMs = ms;
                if (frozen) {
                    frozen = false;
                    t.log("WARN", "ui-agent", threadName(), toolkit + " event thread was blocked for " + ms + " ms", "freeze_end", null);
                }
                sentAt = 0;
            }
        };

        Pinger(String toolkit, String threadPrefix) {
            this.toolkit = toolkit;
            this.threadPrefix = threadPrefix;
        }

        boolean begin() {
            if (sentAt != 0) return false;   // still waiting on the last one
            sentAt = System.currentTimeMillis();
            return true;
        }

        void cancel() { sentAt = 0; }

        void check(long now) {
            long started = sentAt;
            if (started == 0 || frozen) return;
            if (now - started >= t.cfg.freezeMillis) {
                frozen = true;
                freezes++;
                String stack = eventThreadStack();
                t.log("WARN", "ui-agent", threadName(), toolkit + " event thread blocked for " + (now - started) + " ms so far on "
                        + Views.currentPath(), "freeze", stack);
                t.exporter.flush();
            }
        }

        void report() {
            if (!everSeen && !(toolkit.equals("javafx") && FxHook.isRunning())) return;
            Map<String, Object> a = Json.map();
            a.put("ui.toolkit", toolkit);
            if (lastLatencyMs >= 0) t.gauge("ui.event_thread.latency", "ms", lastLatencyMs, a);
            t.gauge("ui.event_thread.latency.max", "ms", maxLatencyMs, a);
            maxLatencyMs = 0;
            t.counter("ui.event_thread.freezes", "{freeze}", freezes, a);
        }

        String threadName() {
            for (Thread th : Thread.getAllStackTraces().keySet()) if (th.getName().startsWith(threadPrefix)) return th.getName();
            return threadPrefix;
        }

        String eventThreadStack() {
            for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
                if (!e.getKey().getName().startsWith(threadPrefix)) continue;
                StringBuilder b = new StringBuilder(e.getKey().getName()).append(" state=").append(e.getKey().getState()).append('\n');
                for (StackTraceElement s : e.getValue()) b.append("    at ").append(s).append('\n');
                return b.toString();
            }
            return "(event thread not found)";
        }
    }
}
