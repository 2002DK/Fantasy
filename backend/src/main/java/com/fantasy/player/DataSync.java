package com.fantasy.player;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** When a cached data set was last refreshed from Sleeper, keyed by data set name. */
@Entity
@Table(name = "data_sync")
public class DataSync {

    @Id
    private String name;
    private Instant syncedAt;
    private int recordCount;

    protected DataSync() {
    }

    DataSync(String name, Instant syncedAt, int recordCount) {
        this.name = name;
        this.syncedAt = syncedAt;
        this.recordCount = recordCount;
    }

    public String getName() {
        return name;
    }

    public Instant getSyncedAt() {
        return syncedAt;
    }

    public int getRecordCount() {
        return recordCount;
    }
}
