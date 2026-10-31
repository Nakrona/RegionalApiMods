package com.regionalapi.api;

import com.regionalapi.core.region.RegionDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraftforge.eventbus.api.Cancelable;
import net.minecraftforge.eventbus.api.Event;

/**
 * Lifecycle and gameplay events fired by RegionalAPI on the Minecraft Forge event bus.
 * <p>
 * Designed for Zero-GC bridge mods by exposing primitive {@code short} region IDs
 * alongside pre-resolved {@link RegionDefinition} references.
 */
public abstract class RegionEvent extends Event {

    private final short regionRawId;
    private final RegionDefinition regionDefinition;

    protected RegionEvent(short regionRawId, RegionDefinition regionDefinition) {
        this.regionRawId = regionRawId;
        this.regionDefinition = regionDefinition;
    }

    /**
     * Returns the compact primitive {@code short} ID of the region for O(1) array indexing.
     */
    public short getRegionRawId() {
        return this.regionRawId;
    }

    /**
     * Returns the pre-resolved {@link RegionDefinition} without performing map lookups.
     */
    public RegionDefinition getRegionDefinition() {
        return this.regionDefinition;
    }

    /**
     * Returns the {@link ResourceLocation} identifier of the region.
     */
    public ResourceLocation getRegionId() {
        return this.regionDefinition.id();
    }

    /**
     * Fired when a {@link ServerPlayer} crosses from one region into another.
     */
    public static final class PlayerEnterRegionEvent extends RegionEvent {

        private final ServerPlayer player;
        private final short previousRegionRawId;
        private final RegionDefinition previousRegionDefinition;

        public PlayerEnterRegionEvent(
                ServerPlayer player,
                short previousRegionRawId,
                RegionDefinition previousRegionDefinition,
                short newRegionRawId,
                RegionDefinition newRegionDefinition
        ) {
            super(newRegionRawId, newRegionDefinition);
            this.player = player;
            this.previousRegionRawId = previousRegionRawId;
            this.previousRegionDefinition = previousRegionDefinition;
        }

        public ServerPlayer getPlayer() {
            return this.player;
        }

        public short getPreviousRegionRawId() {
            return this.previousRegionRawId;
        }

        public RegionDefinition getPreviousRegionDefinition() {
            return this.previousRegionDefinition;
        }

        public ResourceLocation getPreviousRegionId() {
            return this.previousRegionDefinition != null
                    ? this.previousRegionDefinition.id()
                    : null;
        }
    }

    /**
     * Fired when an entity is about to spawn inside a region.
     * Canceling this event prevents the spawn.
     */
    @Cancelable
    public static final class EntitySpawnInRegionEvent extends RegionEvent {

        private final Mob entity;
        private final ServerLevelAccessor level;
        private final int blockX;
        private final int blockY;
        private final int blockZ;
        private final MobSpawnType spawnType;

        public EntitySpawnInRegionEvent(
                Mob entity,
                ServerLevelAccessor level,
                int blockX,
                int blockY,
                int blockZ,
                MobSpawnType spawnType,
                short regionRawId,
                RegionDefinition regionDefinition
        ) {
            super(regionRawId, regionDefinition);
            this.entity = entity;
            this.level = level;
            this.blockX = blockX;
            this.blockY = blockY;
            this.blockZ = blockZ;
            this.spawnType = spawnType;
        }

        public Mob getEntity() {
            return this.entity;
        }

        public ServerLevelAccessor getLevel() {
            return this.level;
        }

        public int getBlockX() {
            return this.blockX;
        }

        public int getBlockY() {
            return this.blockY;
        }

        public int getBlockZ() {
            return this.blockZ;
        }

        public MobSpawnType getSpawnType() {
            return this.spawnType;
        }
    }
}
