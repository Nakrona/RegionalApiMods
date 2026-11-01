package com.regionalapi.forge;

import com.regionalapi.api.RegionEvent;
import com.regionalapi.core.region.RegionDefinition;
import com.regionalapi.network.ClientboundSyncPlayerRegionPacket;
import com.regionalapi.network.ClientboundSyncRegionalSeedPacket;
import com.regionalapi.platform.IPlatformHelper;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.network.PacketDistributor;

import java.nio.file.Path;

/**
 * Forge implementation of {@link IPlatformHelper}.
 */
public final class ForgePlatformHelper implements IPlatformHelper {

    @Override
    public Path getConfigDirectory() {
        return net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get();
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public void sendSyncSeedPacket(ServerPlayer player, long saltedRegionalSeed) {
        PacketDistributor.PLAYER.with(player).send(
                new ClientboundCustomPayloadPacket(new ClientboundSyncRegionalSeedPacket(saltedRegionalSeed))
        );
    }

    @Override
    public void sendSyncPlayerRegionPacket(ServerPlayer player, ResourceLocation regionId) {
        PacketDistributor.PLAYER.with(player).send(
                new ClientboundCustomPayloadPacket(new ClientboundSyncPlayerRegionPacket(regionId))
        );
    }

    @Override
    public void postPlayerRegionEnter(
            ServerPlayer player,
            short previousRegionRawId,
            RegionDefinition previousRegionDefinition,
            short newRegionRawId,
            RegionDefinition newRegionDefinition
    ) {
        MinecraftForge.EVENT_BUS.post(new RegionEvent.PlayerEnterRegionEvent(
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
        return !MinecraftForge.EVENT_BUS.post(event);
    }
}
