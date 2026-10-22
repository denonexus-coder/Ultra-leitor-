package com.bugfunbug.linearreader.mixin;

import com.bugfunbug.linearreader.linear.LinearBackedRegionFile;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Intercepts the two RegionFile methods c2me calls directly on our
 * {@link LinearBackedRegionFile} instances.
 *
 * <h3>Why two intercepts?</h3>
 * c2me's {@code C2MEStorageThread} has two direct RegionFile paths:
 * <ol>
 *   <li><b>Async path</b> ({@code lambda$writeChunk$13}, line ~383) — calls
 *       {@code IRegionFile.invokeWriteChunk} which maps to the private
 *       {@code write(ChunkPos, ByteBuffer)} method. Handled by
 *       {@link #interceptLinearWrite}.</li>
 *   <li><b>Delete path</b> ({@code writeChunk(..., null)}) — calls the
 *       vanilla delete/clear method on the direct {@code RegionFile} handle.
 *       We forward that into {@link LinearBackedRegionFile#clearChunk} in
 *       {@link #interceptLinearClear}.</li>
 * </ol>
 * Both intercepts are no-ops for normal {@code RegionFile} instances — they
 * only activate when {@code this} is a {@link LinearBackedRegionFile}.
 */
@Mixin(RegionFile.class)
public class RegionFileMixin {

    /**
     * Intercepts c2me's async write path.
     *
     * <p>c2me calls {@code write(ChunkPos, ByteBuffer)} directly via its
     * {@code IRegionFile.invokeWriteChunk} accessor mixin, bypassing
     * {@code getChunkDataOutputStream}.  We receive the raw MC chunk stream
     * (5-byte header + compressed NBT) and delegate to
     * {@link LinearBackedRegionFile#writeFromBuffer}.
     */
    @Inject(
            method = "write(Lnet/minecraft/world/level/ChunkPos;Ljava/nio/ByteBuffer;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void interceptLinearWrite(ChunkPos pos, ByteBuffer buffer, CallbackInfo ci) {
        if (!((Object) this instanceof LinearBackedRegionFile backed)) return;
        try {
            backed.writeFromBuffer(pos, buffer);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        ci.cancel();
    }

    /**
     * Intercepts c2me's direct delete path.
     *
     * <p>Across the C2ME rewrite-era branches we inspected, chunk deletion goes
     * through the direct {@code RegionFile} handle that {@code getRegionFile()}
     * returns, using Yarn names like {@code delete} or older obfuscated names
     * that map back to Mojang's {@code clear(ChunkPos)}. Our
     * {@link LinearBackedRegionFile} was created with {@code Unsafe}, so the
     * vanilla sector tables are uninitialized and must never run.
     */
    @Inject(
            method = "clear(Lnet/minecraft/world/level/ChunkPos;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void interceptLinearClear(ChunkPos pos, CallbackInfo ci) {
        if (!((Object) this instanceof LinearBackedRegionFile backed)) return;
        backed.clearChunk(pos);
        ci.cancel();
    }
}
