package com.regionalapi.neoforge;

import com.regionalapi.api.RegionEvent;
import com.regionalapi.core.region.RegionDefinition;
import com.regionalapi.network.ClientboundSyncRegionalSeedPacket;
import com.regionalapi.platform.IPlatformHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.ServerLevelAccessor;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * NeoForge implementation of {@link IPlatformHelper}.
 */
public final class NeoForgePlatformHelper implements IPlatformHelper {

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public void sendSyncSeedPacket(ServerPlayer player, long saltedRegionalSeed) {
        PacketDistributor.sendToPlayer(player, new ClientboundSyncRegionalSeedPacket(saltedRegionalSeed));
    }

    @Override
    public void postPlayerRegionEnter(
            ServerPlayer player,
            short previousRegionRawId,
            RegionDefinition previousRegionDefinition,
            short newRegionRawId,
            RegionDefinition newRegionDefinition
    ) {
        NeoForge.EVENT_BUS.post(new RegionEvent.PlayerEnterRegionEvent(
                player,
                previousRegionRawId,
                previousRegionDefinition,
                newRegionRawId,
                newRegionDefinition
        ));
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
        RegionEvent.EntitySpawnInRegionEvent event = new RegionEvent.EntitySpawnInRegionEvent(
                mob,
                levelAccessor,
                blockX,
                blockY,
                blockZ,
                spawnType,
                regionRawId,
                definition
        );
        return !NeoForge.EVENT_BUS.post(event).isCanceled();
    }
}
