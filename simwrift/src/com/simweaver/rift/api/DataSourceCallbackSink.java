package com.simweaver.rift.api;

import java.util.UUID;

public interface DataSourceCallbackSink {

    void connectionLost(UUID datasourceId);

    /**
     * 0.9.22: as {@link #connectionLost(UUID)}, with the reason for the log (the RTI's fault
     * description, a socket error).
     */
    default void connectionLost(UUID datasourceId, String reason) {
        connectionLost(datasourceId);
    }

    DataSource getDatasource(UUID uuid);
}
