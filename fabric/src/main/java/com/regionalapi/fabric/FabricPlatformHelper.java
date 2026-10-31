package com.regionalapi.fabric;

import com.regionalapi.core.region.RegionDefinition;
import com.regionalapi.network.ClientboundSyncRegionalSeedPacket;
import com.regionalapi.platform.IPlatformHelper;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.ServerLevelAccessor;

/**
 * Fabric implementation of {@link IPlatformHelper}.
 */
public final class FabricPlatformHelper implements IPlatformHelper {

    @Override
    public boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public void sendSyncSeedPacket(ServerPlayer player, long saltedRegionalSeed) {
        ServerPlayNetworking.send(player, new ClientboundSyncRegionalSeedPacket(saltedRegionalSeed));
    }

    @Override
    public void postPlayerRegionEnter(
            ServerPlayer player,
            short previousRegionRawId,
            RegionDefinition previousRegionDefinition,
            short newRegionRawId,
            RegionDefinition newRegionDefinition
    ) {
        FabricRegionalEvents.PLAYER_ENTER_REGION.invoker().onRegionEnter(
                player,
                previousRegionRawId,
                previousRegionDefinition,
                newRegionRawId,
                newRegionDefinition
        );
    }

    @Override
    public boolean postEntitySpawnInRegion(
            Mob mob,
            ServerLevelAccessor levelAccessor,
            int blockX,
            int blockY,
            int blockZ,
            MobSpawnType spawnType,
            short regionRawId,
            RegionDefinition definition
    ) {
        return FabricRegionalEvents.ENTITY_SPAWN_IN_REGION.invoker().shouldSpawn(
                mob,
                levelAccessor,
                blockX,
                blockY,
                blockZ,
                spawnType,
                regionRawId,
                definition
        );
    }
}
