package io.knura.knurd;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntSupplier;

/**
 * Counts what knurd does and reads the JVM's own health numbers, and writes both in the
 * Prometheus text format for {@link MetricsServer}.
 *
 * <p>Every number is a total across all tags: there are no tag UIDs, tag names, connector names or
 * addresses in here, so the page says how busy knurd is but never who tapped what.
 */
final class Metrics {
    /** Tap results that aren't a {@link TapResult.Status}: the verifier threw, or the caller was slowed down. */
    enum Extra { ERROR, THROTTLED }

    /** Upper bounds, in seconds, for the tap verification time histogram. */
    static final double[] BUCKETS = {0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5};

    private final Map<TapResult.Status, LongAdder> taps = new EnumMap<>(TapResult.Status.class);
    private final Map<Extra, LongAdder> otherTaps = new EnumMap<>(Extra.class);
    private final LongAdder[] buckets = new LongAdder[BUCKETS.length];
    private final LongAdder verifyCount = new LongAdder();
    private final DoubleAdder verifySeconds = new DoubleAdder();
    private final LongAdder actionsStarted = new LongAdder();
    private final Map<String, LongAdder> actions = Map.of(
            "delivered", new LongAdder(), "refused", new LongAdder(), "expired", new LongAdder());
    private volatile IntSupplier knots = () -> 0;

    Metrics() {
        for (TapResult.Status s : TapResult.Status.values()) {
            taps.put(s, new LongAdder());
        }
        for (Extra e : Extra.values()) {
            otherTaps.put(e, new LongAdder());
        }
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] = new LongAdder();
        }
    }

    /** One tap checked, with how long checking it took. */
    void tap(TapResult.Status status, long nanos) {
        taps.get(status).increment();
        double seconds = nanos / 1e9;
        verifyCount.increment();
        verifySeconds.add(seconds);
        for (int i = 0; i < BUCKETS.length; i++) {
            if (seconds <= BUCKETS[i]) {
                buckets[i].increment();
            }
        }
    }

    void tap(Extra what) {
        otherTaps.get(what).increment();
    }

    void actionsStarted(int n) {
        if (n > 0) {
            actionsStarted.add(n);
        }
    }

    /** How one action ended: {@code delivered}, {@code refused} or {@code expired}; others are ignored. */
    void action(String outcome) {
        LongAdder a = actions.get(outcome);
        if (a != null) {
            a.increment();
        }
    }

    /** Where to read how many tags have actions set up. */
    void knots(IntSupplier knots) {
        this.knots = knots;
    }

    /** Everything, in the Prometheus text exposition format (version 0.0.4). */
    String render() {
        StringBuilder out = new StringBuilder(4096);

        head(out, "knurd_taps_total", "counter", "Taps checked, by result. ok is the only one that runs actions.");
        for (Map.Entry<TapResult.Status, LongAdder> e : taps.entrySet()) {
            line(out, "knurd_taps_total", "result", e.getKey().jsonName(), e.getValue().sum());
        }
        for (Map.Entry<Extra, LongAdder> e : otherTaps.entrySet()) {
            line(out, "knurd_taps_total", "result", e.getKey().name().toLowerCase(Locale.ROOT), e.getValue().sum());
        }

        head(out, "knurd_tap_verify_seconds", "histogram", "How long checking a tap took.");
        for (int i = 0; i < BUCKETS.length; i++) {
            line(out, "knurd_tap_verify_seconds_bucket", "le", number(BUCKETS[i]), buckets[i].sum());
        }
        long count = verifyCount.sum();
        line(out, "knurd_tap_verify_seconds_bucket", "le", "+Inf", count);
        line(out, "knurd_tap_verify_seconds_sum", verifySeconds.sum());
        line(out, "knurd_tap_verify_seconds_count", count);

        head(out, "knurd_actions_started_total", "counter", "Actions started by genuine taps.");
        line(out, "knurd_actions_started_total", actionsStarted.sum());
        head(out, "knurd_actions_total", "counter",
                "How actions ended: delivered, refused by the other end, or expired after retrying. Tests aren't counted.");
        for (String outcome : new String[] {"delivered", "refused", "expired"}) {
            line(out, "knurd_actions_total", "outcome", outcome, actions.get(outcome).sum());
        }
        head(out, "knurd_tags_with_actions", "gauge", "Tags that have actions set up.");
        line(out, "knurd_tags_with_actions", knots.getAsInt());

        jvm(out);
        return out.toString();
    }

    /** The JVM's health: memory, threads, garbage collection, CPU and open files. */
    private static void jvm(StringBuilder out) {
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        head(out, "jvm_info", "gauge", "The Java runtime knurd runs on.");
        out.append("jvm_info{version=\"").append(escape(Runtime.version().toString()))
                .append("\",vendor=\"").append(escape(String.valueOf(runtime.getVmVendor())))
                .append("\",runtime=\"").append(escape(String.valueOf(runtime.getVmName()))).append("\"} 1\n");

        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        MemoryUsage nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
        head(out, "jvm_memory_used_bytes", "gauge", "Memory in use. heap holds knurd's data; nonheap holds code and classes.");
        line(out, "jvm_memory_used_bytes", "area", "heap", heap.getUsed());
        line(out, "jvm_memory_used_bytes", "area", "nonheap", nonHeap.getUsed());
        head(out, "jvm_memory_committed_bytes", "gauge", "Memory the JVM has reserved from the system.");
        line(out, "jvm_memory_committed_bytes", "area", "heap", heap.getCommitted());
        line(out, "jvm_memory_committed_bytes", "area", "nonheap", nonHeap.getCommitted());
        head(out, "jvm_memory_max_bytes", "gauge", "The most memory the JVM may use, or -1 for no limit.");
        line(out, "jvm_memory_max_bytes", "area", "heap", heap.getMax());
        line(out, "jvm_memory_max_bytes", "area", "nonheap", nonHeap.getMax());

        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        head(out, "jvm_threads_current", "gauge", "Platform threads alive now (virtual threads aren't counted).");
        line(out, "jvm_threads_current", threads.getThreadCount());
        head(out, "jvm_threads_daemon", "gauge", "Daemon platform threads alive now.");
        line(out, "jvm_threads_daemon", threads.getDaemonThreadCount());
        head(out, "jvm_threads_peak", "gauge", "Most platform threads alive at once since knurd started.");
        line(out, "jvm_threads_peak", threads.getPeakThreadCount());

        head(out, "jvm_classes_loaded", "gauge", "Classes loaded now.");
        line(out, "jvm_classes_loaded", ManagementFactory.getClassLoadingMXBean().getLoadedClassCount());

        head(out, "jvm_gc_collections_total", "counter", "Garbage collections, by collector.");
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            line(out, "jvm_gc_collections_total", "gc", gc.getName(), Math.max(0, gc.getCollectionCount()));
        }
        head(out, "jvm_gc_collection_seconds_total", "counter", "Time spent collecting garbage, by collector.");
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            line(out, "jvm_gc_collection_seconds_total", "gc", gc.getName(),
                    number(Math.max(0, gc.getCollectionTime()) / 1000.0));
        }

        head(out, "process_start_time_seconds", "gauge", "When knurd started, in seconds since 1970.");
        line(out, "process_start_time_seconds", number(runtime.getStartTime() / 1000.0));
        head(out, "process_uptime_seconds", "gauge", "How long knurd has been running.");
        line(out, "process_uptime_seconds", number(runtime.getUptime() / 1000.0));

        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        head(out, "process_available_processors", "gauge", "CPU cores the JVM may use.");
        line(out, "process_available_processors", os.getAvailableProcessors());
        if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
            long cpu = sun.getProcessCpuTime();
            if (cpu >= 0) {
                head(out, "process_cpu_seconds_total", "counter", "CPU time knurd has used.");
                line(out, "process_cpu_seconds_total", number(cpu / 1e9));
            }
        }
        if (os instanceof com.sun.management.UnixOperatingSystemMXBean unix) {
            head(out, "process_open_fds", "gauge", "Files and connections knurd has open.");
            line(out, "process_open_fds", unix.getOpenFileDescriptorCount());
            head(out, "process_max_fds", "gauge", "The most files and connections knurd may have open.");
            line(out, "process_max_fds", unix.getMaxFileDescriptorCount());
        }
    }

    private static void head(StringBuilder out, String name, String type, String help) {
        out.append("# HELP ").append(name).append(' ').append(help).append('\n');
        out.append("# TYPE ").append(name).append(' ').append(type).append('\n');
    }

    private static void line(StringBuilder out, String name, long value) {
        out.append(name).append(' ').append(value).append('\n');
    }

    private static void line(StringBuilder out, String name, double value) {
        line(out, name, number(value));
    }

    private static void line(StringBuilder out, String name, String value) {
        out.append(name).append(' ').append(value).append('\n');
    }

    private static void line(StringBuilder out, String name, String label, String labelValue, long value) {
        line(out, name, label, labelValue, Long.toString(value));
    }

    private static void line(StringBuilder out, String name, String label, String labelValue, String value) {
        out.append(name).append('{').append(label).append("=\"").append(escape(labelValue)).append("\"} ")
                .append(value).append('\n');
    }

    /** A number as Prometheus reads it: no exponent for ordinary values, no locale. */
    static String number(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        return Double.toString(v);
    }

    /** Label values escape backslash, double quote and newline. */
    static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
