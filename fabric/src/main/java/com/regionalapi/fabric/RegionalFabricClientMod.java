package com.regionalapi.fabric;

import com.regionalapi.network.ClientboundSyncRegionalSeedPacket;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * Client-side Fabric entry point for receiving synced regional seed.
 */
public final class RegionalFabricClientMod implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(
                ClientboundSyncRegionalSeedPacket.TYPE,
                (payload, context) -> ClientboundSyncRegionalSeedPacket.setClientRegionalSeed(payload.regionalSeed())
        );
    }
}
