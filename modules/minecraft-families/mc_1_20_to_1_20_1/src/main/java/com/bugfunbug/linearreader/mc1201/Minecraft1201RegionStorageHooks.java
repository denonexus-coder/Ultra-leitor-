package com.bugfunbug.linearreader.mc1201;

import com.bugfunbug.linearreader.linear.IdleRecompressor;
import com.bugfunbug.linearreader.linear.MCAConverter;
import com.bugfunbug.linearreader.minecraftapi.RegionStorageHooks;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;

import java.io.IOException;
import java.nio.file.Path;

public final class Minecraft1201RegionStorageHooks implements RegionStorageHooks {

    public static final Minecraft1201RegionStorageHooks INSTANCE = new Minecraft1201RegionStorageHooks();

    private Minecraft1201RegionStorageHooks() {}

    @Override
    public void onStorageOpened(Path regionFolder) {
        if (regionFolder == null) return;
        MCAConverter.convertFolder(regionFolder);
        IdleRecompressor.registerFolder(regionFolder);
    }

    @Override
    public Path resolveLinearRegionPath(Path regionFolder, ChunkPos chunkPos) {
        return regionFolder.resolve(
                "r." + chunkPos.getRegionX() + "." + chunkPos.getRegionZ() + ".linear");
    }

    @Override
    public RegionFile openVanillaRegionFile(Path regionFilePath, Path regionFolder, boolean sync) throws IOException {
        return new RegionFile(regionFilePath, regionFolder, sync);
    }
}
