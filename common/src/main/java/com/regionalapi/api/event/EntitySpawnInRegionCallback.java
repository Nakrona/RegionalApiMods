package com.regionalapi.api.event;

import com.regionalapi.core.region.RegionDefinition;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.ServerLevelAccessor;

/**
 * Functional callback interface fired when an entity is evaluated for spawning in a region.
 * Return {@code false} to cancel the spawn.
 */
@FunctionalInterface
public interface EntitySpawnInRegionCallback {

    /**
     * Evaluates whether an entity spawn should proceed.
     *
     * @return {@code true} to allow spawning, {@code false} to cancel.
     */
    boolean shouldSpawn(
            Mob mob,
            ServerLevelAccessor levelAccessor,
            int blockX,
            int blockY,
            int blockZ,
            MobSpawnType spawnType,
            short regionRawId,
            RegionDefinition definition
    );
}
