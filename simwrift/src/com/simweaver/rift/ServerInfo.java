package com.simweaver.rift;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Server facts for {@code GET /api/serverinfo}: version, process CPU and memory, JVM basics.
 * JDK only ({@code java.lang.management}, plus {@code com.sun.management} where the JVM offers it,
 * as HotSpot/OpenJDK do on Windows and Linux).
 *
 * <p><b>CPU</b> is the process's share of the whole machine in percent (0-100, all cores, as the
 * Windows Task Manager shows it), measured between two calls from the process CPU time; calls less
 * than {@link #MIN_SAMPLE_MILLIS} apart reuse the last value, so several open pages do not shrink
 * the window. The first call gives the average since the JVM started.
 *
 * <p>Thread safe.
 */
public final class ServerInfo {

    /** Shortest CPU measuring window. */
    public static final long MIN_SAMPLE_MILLIS = 1000;

    private final String name;
    private final Supplier<String> version;
    private final RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
    private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    private final OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
    private final com.sun.management.OperatingSystemMXBean sunOs =
            os instanceof com.sun.management.OperatingSystemMXBean ? (com.sun.management.OperatingSystemMXBean) os : null;
    private final int cores = Runtime.getRuntime().availableProcessors();

    // last CPU sample (guarded by this)
    private long lastWallNanos;
    private long lastCpuNanos = -1;
    private Double lastPercent;

    /**
     * @param name    product name, e.g. "SimWeaver Rift"
     * @param version supplies the version text, e.g. {@code Version::getVersion}
     */
    public ServerInfo(String name, Supplier<String> version) {
        this.name = name;
        this.version = version;
    }

    /** The facts as a JSON-ready map (see {@link #toMap}). */
    public Map<String, Object> snapshot(int datasourceCount) {
        return toMap(datasourceCount);
    }

    /**
     * <pre>
     * {"name", "version", "started", "now", "uptimeseconds", "pid", "java", "os",
     *  "cpu":    {"process": %, "system": %, "cores": n},          % = null when not measurable
     *  "memory": {"heapused", "heapcommitted", "heapmax", "nonheapused",
     *             "processcommitted", "physicaltotal", "physicalfree"},   bytes; null when unknown
     *  "threads": n, "datasources": n}
     * </pre>
     */
    Map<String, Object> toMap(int datasourceCount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("version", versionText());
        m.put("started", iso(runtime.getStartTime()));
        m.put("now", iso(System.currentTimeMillis()));
        m.put("uptimeseconds", runtime.getUptime() / 1000);
        m.put("pid", ProcessHandle.current().pid());
        m.put("java", System.getProperty("java.version"));
        m.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));

        Map<String, Object> cpu = new LinkedHashMap<>();
        cpu.put("process", processCpuPercent());
        cpu.put("system", systemCpuPercent());
        cpu.put("cores", cores);
        m.put("cpu", cpu);

        MemoryUsage heap = memory.getHeapMemoryUsage();
        MemoryUsage nonHeap = memory.getNonHeapMemoryUsage();
        Map<String, Object> mem = new LinkedHashMap<>();
        mem.put("heapused", heap.getUsed());
        mem.put("heapcommitted", heap.getCommitted());
        mem.put("heapmax", heap.getMax() < 0 ? null : heap.getMax());
        mem.put("nonheapused", nonHeap.getUsed());
        mem.put("processcommitted", sunOs == null ? null : positive(sunOs.getCommittedVirtualMemorySize()));
        mem.put("physicaltotal", sunOs == null ? null : positive(sunOs.getTotalPhysicalMemorySize()));
        mem.put("physicalfree", sunOs == null ? null : positive(sunOs.getFreePhysicalMemorySize()));
        m.put("memory", mem);

        m.put("threads", ManagementFactory.getThreadMXBean().getThreadCount());
        m.put("datasources", datasourceCount);
        return m;
    }

    private String versionText() {
        try {
            String v = version == null ? null : version.get();
            return v == null || v.isEmpty() ? "unknown" : v;
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    /** Process CPU in percent of the whole machine since the previous sample; null if not measurable. */
    synchronized Double processCpuPercent() {
        if (sunOs == null) return null;
        long cpuNow = sunOs.getProcessCpuTime();
        long wallNow = System.nanoTime();
        if (cpuNow < 0) return null;
        if (lastCpuNanos < 0) {                                   // first call: average since start
            long upNanos = Math.max(1, runtime.getUptime()) * 1_000_000L;
            lastPercent = round1(100.0 * cpuNow / upNanos / cores);
        } else {
            long wall = wallNow - lastWallNanos;
            if (wall < MIN_SAMPLE_MILLIS * 1_000_000L) return lastPercent;
            lastPercent = round1(100.0 * (cpuNow - lastCpuNanos) / wall / cores);
        }
        lastCpuNanos = cpuNow;
        lastWallNanos = wallNow;
        return lastPercent;
    }

    /** Whole-machine CPU in percent as the JVM reports it (recent average); null if not available. */
    Double systemCpuPercent() {
        if (sunOs == null) return null;
        @SuppressWarnings("deprecation")       // getCpuLoad() replaces it from Java 14; this builds on 11
        double load = sunOs.getSystemCpuLoad();
        return load < 0 || Double.isNaN(load) ? null : round1(100.0 * load);
    }

    private static Double round1(double percent) {
        double p = Math.max(0, Math.min(100, percent));
        return Math.round(p * 10) / 10.0;
    }

    private static Long positive(long v) {
        return v < 0 ? null : v;
    }

    private static String iso(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).truncatedTo(ChronoUnit.MILLIS).toString();
    }
}
