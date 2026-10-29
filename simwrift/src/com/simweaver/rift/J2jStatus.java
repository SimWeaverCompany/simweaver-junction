package com.simweaver.rift;

import com.simweaver.j2j.admin.AdminPlane;
import com.simweaver.junction.JunctionManager;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;

/** What this server tells the other J2J members about itself (0.10.0): CPU, heap, running gateways. */
final class J2jStatus implements AdminPlane.StatusSource {

    private final JunctionManager junction;
    private final ServerInfo cpu = new ServerInfo("SimWeaver", () -> "");

    J2jStatus(JunctionManager junction) {
        this.junction = junction;
    }

    @Override
    public double cpuPercent() {
        Double d = cpu.processCpuPercent();
        return d == null ? -1 : d;
    }

    @Override
    public long heapUsed() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    @Override
    public long heapMax() {
        MemoryUsage h = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        return h.getMax();
    }

    @Override
    public int gateways() {
        return junction == null ? 0 : junction.gateways().size();
    }

    @Override
    public int sessions() {
        return 0;
    }
}
