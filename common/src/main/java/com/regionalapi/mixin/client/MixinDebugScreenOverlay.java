package com.regionalapi.mixin.client;

import com.regionalapi.client.ClientRegionTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Mixin injecting the "Region: <region_id>" line into the vanilla F3 debug screen
 * directly above "Biome: <biome_id>".
 */
@Mixin(DebugScreenOverlay.class)
public abstract class MixinDebugScreenOverlay {

    @Shadow
    @Final
    private Minecraft minecraft;

    @Inject(method = "getGameInformation", at = @At("RETURN"))
    private void regionalapi$addRegionToGameInformation(CallbackInfoReturnable<List<String>> cir) {
        List<String> list = cir.getReturnValue();
        if (list == null) {
            return;
        }

        BlockPos cameraPos = (this.minecraft != null && this.minecraft.getCameraEntity() != null)
                ? this.minecraft.getCameraEntity().blockPosition()
                : null;
        String regionText = ClientRegionTracker.getDebugRegionString(this.minecraft, cameraPos);

        int biomeIndex = -1;
        for (int i = 0; i < list.size(); i++) {
            String line = list.get(i);
            if (line != null && line.startsWith("Biome:")) {
                biomeIndex = i;
                break;
            }
        }

        if (biomeIndex >= 0) {
            list.add(biomeIndex, regionText);
        } else {
            list.add(regionText);
        }
    }
}
