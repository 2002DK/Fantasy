package com.fantasy.player;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DataSyncRepository extends JpaRepository<DataSync, String> {
}
