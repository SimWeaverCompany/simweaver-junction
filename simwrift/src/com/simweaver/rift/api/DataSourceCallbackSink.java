package com.simweaver.rift.api;

import java.util.UUID;

public interface DataSourceCallbackSink {

    void connectionLost(UUID datasourceId);

    DataSource getDatasource(UUID uuid);
}
