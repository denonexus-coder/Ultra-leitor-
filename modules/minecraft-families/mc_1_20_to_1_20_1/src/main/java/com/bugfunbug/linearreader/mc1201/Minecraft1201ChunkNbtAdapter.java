package com.bugfunbug.linearreader.mc1201;

import com.bugfunbug.linearreader.minecraftapi.ChunkNbtAdapter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;

import java.util.Set;

public final class Minecraft1201ChunkNbtAdapter implements ChunkNbtAdapter {

    public static final Minecraft1201ChunkNbtAdapter INSTANCE = new Minecraft1201ChunkNbtAdapter();

    private Minecraft1201ChunkNbtAdapter() {}

    @Override
    public CompoundTag unwrapChunkTag(CompoundTag rawTag) {
        return rawTag.contains("Level", Tag.TAG_COMPOUND)
                ? rawTag.getCompound("Level")
                : rawTag;
    }

    @Override
    public boolean hasCompound(CompoundTag tag, String key) {
        return tag.contains(key, Tag.TAG_COMPOUND);
    }

    @Override
    public CompoundTag getCompoundOrEmpty(CompoundTag tag, String key) {
        return hasCompound(tag, key) ? tag.getCompound(key) : new CompoundTag();
    }

    @Override
    public boolean hasNumeric(CompoundTag tag, String key) {
        return tag.contains(key, Tag.TAG_ANY_NUMERIC);
    }

    @Override
    public long getLongOrDefault(CompoundTag tag, String key, long fallback) {
        return hasNumeric(tag, key) ? tag.getLong(key) : fallback;
    }

    @Override
    public ListTag getListOrEmpty(CompoundTag tag, String key, int expectedElementType) {
        return tag.contains(key, Tag.TAG_LIST) ? tag.getList(key, expectedElementType) : new ListTag();
    }

    @Override
    public Set<String> keySet(CompoundTag tag) {
        return tag.getAllKeys();
    }

    @Override
    public boolean hasLongArray(CompoundTag tag, String key) {
        return tag.contains(key, Tag.TAG_LONG_ARRAY);
    }

    @Override
    public boolean hasNonEmptyLongArray(CompoundTag tag, String key) {
        if (!hasLongArray(tag, key)) {
            return false;
        }
        Tag value = tag.get(key);
        return value instanceof LongArrayTag array && array.size() > 0;
    }
}
