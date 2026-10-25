package com.simweaver.rift.api;

import com.simweaver.common.json.Json;
import com.simweaver.rift.DatasourceManager;
import com.simweaver.rift.db.RiftDatabase;

import java.util.UUID;

public interface DataSource extends Json.Writable {


    DatasourceManager.OpResult connectJoinSub();

    DatasourceManager.OpResult disconnect();

    RiftDatabase getDatabase();

    UUID getId();

}
