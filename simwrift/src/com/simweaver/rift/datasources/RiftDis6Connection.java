package com.simweaver.rift.datasources;

import com.simweaver.rift.DatasourceConfig;
import com.simweaver.rift.api.DataSourceCallbackSink;

/**
 * DIS 6 (IEEE 1278.1a-1998) datasource. Accepts protocol versions 5 and 6. See
 * {@link RiftDisConnection} for the behaviour and the DIS to RPR mapping.
 */
public class RiftDis6Connection extends RiftDisConnection {

    /**
     * @param config       the datasource configuration
     * @param exercise     the exercise id (in place of the federation name); null or blank = the
     *                     configuration's {@code datasource.dis.exerciseId}
     * @param callbackSink told when receiving fails while running
     */
    public RiftDis6Connection(DatasourceConfig config, String exercise, DataSourceCallbackSink callbackSink) {
        super(config, exercise, callbackSink, 6);
    }
}
