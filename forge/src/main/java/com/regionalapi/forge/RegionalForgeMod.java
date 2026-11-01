package com.regionalapi.forge;

import com.regionalapi.RegionalModConstants;
import com.regionalapi.client.ClientRegionTracker;
import com.regionalapi.core.config.RegionalApiConfig;
import com.regionalapi.network.ClientboundSyncPlayerRegionPacket;
import com.regionalapi.network.ClientboundSyncRegionalSeedPacket;
import com.regionalapi.platform.IPlatformHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.ChannelBuilder;

/**
 * Main Forge entry point for **RegionalAPI** (`regionalapi`).
 */
@Mod(RegionalModConstants.MOD_ID)
public final class RegionalForgeMod {

    public RegionalForgeMod() {
        RegionalApiConfig.loadOrCreate(IPlatformHelper.getInstance().getConfigDirectory());
        MinecraftForge.EVENT_BUS.register(new RegionForgeGameplayListeners());

        ChannelBuilder.named(ResourceLocation.fromNamespaceAndPath(RegionalModConstants.MOD_ID, "main"))
                .optional()
                .payloadChannel()
                .play()
                .clientbound()
                .addMain(
                        ClientboundSyncRegionalSeedPacket.TYPE,
                        ClientboundSyncRegionalSeedPacket.STREAM_CODEC.cast(),
                        (payload, context) -> ClientboundSyncRegionalSeedPacket.setClientRegionalSeed(payload.regionalSeed())
                )
                .addMain(
                        ClientboundSyncPlayerRegionPacket.TYPE,
                        ClientboundSyncPlayerRegionPacket.STREAM_CODEC.cast(),
                        (payload, context) -> ClientRegionTracker.setCurrentRegion(payload.regionId())
                )
                .build();

        RegionalModConstants.LOGGER.info("RegionalAPI Forge initialized (2D Laguerre-Voronoi & 3D Biome Masking Engine).");
    }
}
