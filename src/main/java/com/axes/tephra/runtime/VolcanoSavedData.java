package com.axes.tephra.runtime;

import com.axes.tephra.block.VolcanoPhase;
import com.axes.tephra.block.VolcanoType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Per-dimension registry of all volcanoes. Authority for identity while chunks are unloaded.
 */
public final class VolcanoSavedData extends SavedData {
    public static final String DATA_NAME = "tephra_volcanoes";

    private static final Factory<VolcanoSavedData> FACTORY = new Factory<>(
            VolcanoSavedData::new,
            VolcanoSavedData::load
    );

    private final Map<Long, VolcanoRecord> volcanoes = new HashMap<>();

    public static VolcanoSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public Optional<VolcanoRecord> get(BlockPos pos) {
        return Optional.ofNullable(volcanoes.get(pos.asLong()));
    }

    public Collection<VolcanoRecord> all() {
        return Collections.unmodifiableCollection(volcanoes.values());
    }

    public int size() {
        return volcanoes.size();
    }

    public VolcanoRecord register(BlockPos pos, VolcanoType type, VolcanoPhase phase, long gameTime, long worldSeed) {
        long key = pos.asLong();
        VolcanoRecord existing = volcanoes.get(key);
        if (existing != null) {
            existing.setType(type);
            existing.setPhase(phase);
            existing.setLastSimGameTime(gameTime);
            setDirty();
            return existing;
        }

        long personality = worldSeed ^ key;
        VolcanoRecord created = new VolcanoRecord(pos, type, phase, personality);
        created.setLastSimGameTime(gameTime);
        volcanoes.put(key, created);
        setDirty();
        return created;
    }

    public void unregister(BlockPos pos) {
        if (volcanoes.remove(pos.asLong()) != null) {
            setDirty();
        }
    }

    public void put(VolcanoRecord record) {
        volcanoes.put(record.getPos().asLong(), record);
        setDirty();
    }

    public void markDirty() {
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (VolcanoRecord record : volcanoes.values()) {
            list.add(record.save());
        }
        tag.put("Volcanoes", list);
        return tag;
    }

    public static VolcanoSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        VolcanoSavedData data = new VolcanoSavedData();
        ListTag list = tag.getList("Volcanoes", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            VolcanoRecord record = VolcanoRecord.load(list.getCompound(i), registries);
            data.volcanoes.put(record.getPos().asLong(), record);
        }
        return data;
    }
}
