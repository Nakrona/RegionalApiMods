package com.regionalapi.neoforge;

import com.regionalapi.RegionalModConstants;
import com.regionalapi.client.ClientRegionTracker;
import com.regionalapi.core.config.RegionalApiConfig;
import com.regionalapi.network.ClientboundSyncPlayerRegionPacket;
import com.regionalapi.network.ClientboundSyncRegionalSeedPacket;
import com.regionalapi.platform.IPlatformHelper;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Main NeoForge entry point for **RegionalAPI** (`regionalapi`).
 */
@Mod(RegionalModConstants.MOD_ID)
public final class RegionalNeoForgeMod {

    public RegionalNeoForgeMod(IEventBus modEventBus) {
        RegionalApiConfig.loadOrCreate(IPlatformHelper.getInstance().getConfigDirectory());
        modEventBus.addListener(this::onRegisterPayloads);
        NeoForge.EVENT_BUS.register(new RegionGameplayListeners());
        RegionalModConstants.LOGGER.info("RegionalAPI NeoForge initialized (2D Laguerre-Voronoi & 3D Biome Masking Engine).");
    }

    private void onRegisterPayloads(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(RegionalModConstants.MOD_ID).optional();
        registrar.playToClient(
                ClientboundSyncRegionalSeedPacket.TYPE,
                ClientboundSyncRegionalSeedPacket.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientboundSyncRegionalSeedPacket.setClientRegionalSeed(payload.regionalSeed()))
        );
        registrar.playToClient(
                ClientboundSyncPlayerRegionPacket.TYPE,
                ClientboundSyncPlayerRegionPacket.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientRegionTracker.setCurrentRegion(payload.regionId()))
        );
    }
}
