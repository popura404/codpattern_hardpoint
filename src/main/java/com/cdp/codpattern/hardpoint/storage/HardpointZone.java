package com.cdp.codpattern.hardpoint.storage;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.phasetranscrystal.fpsmatch.core.data.AreaData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Both marked blocks belong to the hill, including when they share a coordinate. */
public record HardpointZone(String name, AreaData area) {
    static final Codec<AreaData> AREA_CODEC = RecordCodecBuilder.create(i -> i.group(
            BlockPos.CODEC.fieldOf("Position1").forGetter(AreaData::pos1),
            BlockPos.CODEC.fieldOf("Position2").forGetter(AreaData::pos2)
    ).apply(i, AreaData::new));
    public static final Codec<HardpointZone> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("name").forGetter(HardpointZone::name),
            AREA_CODEC.fieldOf("area").forGetter(HardpointZone::area)
    ).apply(i, HardpointZone::new));

    public HardpointZone {
        name = name.strip();
        if (name.isEmpty() || name.length() > 32 || name.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(HardpointDeployment.PREFIX + "invalid_name");
        area = new AreaData(area.pos1().immutable(), area.pos2().immutable());
    }

    public boolean contains(BlockPos pos) { return contains(Vec3.atCenterOf(pos)); }
    public boolean contains(Vec3 pos) { return captureArea().getBlockInclusiveAABB().contains(pos); }

    /** A flat floor selection includes feet standing on its top face. Taller selections keep their exact marked heights. */
    public AreaData captureArea() { return captureArea(area); }

    public static AreaData captureArea(AreaData marked) {
        if (marked.pos1().getY() != marked.pos2().getY()) return marked;
        return new AreaData(marked.pos1(), marked.pos2().above());
    }
}
