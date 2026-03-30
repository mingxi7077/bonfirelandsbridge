package com.bonfire.landsbridge.service;

import com.bonfire.landsbridge.model.RentalSnapshot;

import java.util.Optional;

public interface RentalSnapshotRepository extends AutoCloseable {

    Optional<RentalSnapshot> findSnapshot(String landId, String areaId);

    String name();

    @Override
    void close();
}
