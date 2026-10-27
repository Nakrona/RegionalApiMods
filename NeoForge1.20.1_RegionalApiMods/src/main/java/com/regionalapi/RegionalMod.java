package com.regionalapi;

import com.regionalapi.api.RegionGameplayListeners;
import com.regionalapi.core.cache.ChunkRegionAttachment;
import com.regionalapi.network.ClientboundSyncRegionalSeedPacket;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main NeoForge entry point for **RegionalAPI** (`regionalapi`).
 * <p>
 * Registers NeoForge data attachments, custom network payload handlers, and zero-overhead
 * server/gameplay lifecycle listeners.
 */
@Mod(RegionalMod.MOD_ID)
public final class RegionalMod {

    public static final String MOD_ID = "regionalapi";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public RegionalMod(IEventBus modEventBus) {
        ChunkRegionAttachment.ATTACHMENT_TYPES.register(modEventBus);
        modEventBus.addListener(this::onRegisterPayloads);

        NeoForge.EVENT_BUS.register(new RegionGameplayListeners());
        LOGGER.info("RegionalAPI initialized (2D Laguerre-Voronoi & 3D Biome Masking Engine).");
    }

    private void onRegisterPayloads(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar("1.0.0").optional();
        registrar.playToClient(
                ClientboundSyncRegionalSeedPacket.TYPE,
                ClientboundSyncRegionalSeedPacket.STREAM_CODEC,
                ClientboundSyncRegionalSeedPacket::handleClient
        );
    }
}
