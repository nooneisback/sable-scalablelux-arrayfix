package com.example.examplemod.mixin;

import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fixes ArrayIndexOutOfBoundsException in ScalableLux's StarLightEngine when
 * creating Sable objects (plots / sub-levels).
 * <p>
 * Sable places plots ~1.28M chunks out (SubLevelContainer.DEFAULT_ORIGIN), and
 * its chunk-routing mixins can make the light-window cache return a chunk whose
 * ChunkPos doesn't match the requested coordinates (e.g. the (0,0) empty-chunk
 * placeholder). ScalableLux then re-derives chunkX/Z from chunk.getPos() and
 * indexes the 25-slot window out of bounds (crash index -7682932 = a (0,0)
 * chunk read through a window centered at (1280004, 1280588)).
 * <p>
 * This guard drops mismatched chunks from the window, which ScalableLux already
 * treats exactly like an unloaded chunk (lighting for it is simply skipped).
 * <p>
 * String-targeted + required = false so this compat mod builds and loads even
 * without ScalableLux installed.
 */
@Mixin(targets = "ca.spottedleaf.starlight.common.light.StarLightEngine", remap = false)
public abstract class StarLightEngineMixin {

    /**
     * Enforce cache invariant: chunk stored at (chunkX, chunkZ) must carry that
     * exact position, otherwise the engine's window arithmetic breaks.
     */
    @Inject(method = "getChunkInCache", at = @At("RETURN"), cancellable = true, require = 0)
    private void sablescalableluxcompat$verifyChunkPos(final int chunkX, final int chunkZ, final CallbackInfoReturnable<ChunkAccess> cir) {
        final ChunkAccess chunk = cir.getReturnValue();
        if (chunk != null && (chunk.getPos().x != chunkX || chunk.getPos().z != chunkZ)) {
            cir.setReturnValue(null);
        }
    }
}
