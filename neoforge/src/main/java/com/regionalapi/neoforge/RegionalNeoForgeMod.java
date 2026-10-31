package com.regionalapi.neoforge;

import com.regionalapi.RegionalModConstants;
import com.regionalapi.network.ClientboundSyncRegionalSeedPacket;
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
        modEventBus.addListener(this::onRegisterPayloads);
        NeoForge.EVENT_BUS.register(new RegionGameplayListeners());
        RegionalModConstants.LOGGER.info("RegionalAPI NeoForge initialized (2D Laguerre-Voronoi & 3D Biome Masking Engine).");
    }

    private void onRegisterPayloads(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar("1.0.0").optional();
        registrar.playToClient(
                ClientboundSyncRegionalSeedPacket.TYPE,
                ClientboundSyncRegionalSeedPacket.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientboundSyncRegionalSeedPacket.setClientRegionalSeed(payload.regionalSeed()))
        );
    }
}
