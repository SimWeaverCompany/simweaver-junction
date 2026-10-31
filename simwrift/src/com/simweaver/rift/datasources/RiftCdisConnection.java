package com.simweaver.rift.datasources;

import com.simweaver.common.dis.cdis.CdisCodec;
import com.simweaver.rift.DatasourceConfig;
import com.simweaver.rift.api.DataSourceCallbackSink;

/**
 * C-DIS (SISO-STD-023-2024) datasource (0.9.18): listens for C-DIS, decodes it to DIS 7 (full and
 * partial updates, see {@link CdisCodec}) and then works as {@link RiftDis7Connection}. The
 * decoder's heartbeats and timeouts come from {@code datasource.cdis.*}.
 */
public class RiftCdisConnection extends RiftDisConnection {

    /**
     * @param config       the datasource configuration
     * @param exercise     the exercise id; null or blank = the configuration's
     * @param callbackSink told when receiving fails while running
     */
    public RiftCdisConnection(DatasourceConfig config, String exercise, DataSourceCallbackSink callbackSink) {
        super(config, exercise, callbackSink, 7, new CdisCodec(config.getCdisSettings()));
    }
}
