package com.cdp.codpattern.hardpoint.deploy;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

record HardpointView(UUID session, String mapName, HardpointEditor.Field field, int index, String input,
                     List<String> zones, int red, int blue, boolean exit, Optional<BlockPos> first,
                     Optional<BlockPos> second, boolean editable, Component feedback) {
    void write(FriendlyByteBuf buf) {
        buf.writeUUID(session);
        buf.writeUtf(mapName, 100);
        buf.writeEnum(field);
        buf.writeVarInt(index);
        buf.writeUtf(input, 100);
        buf.writeCollection(zones, (b,s) -> b.writeUtf(s, 32));
        buf.writeVarInt(red);
        buf.writeVarInt(blue);
        buf.writeBoolean(exit);
        buf.writeOptional(first, FriendlyByteBuf::writeBlockPos);
        buf.writeOptional(second, FriendlyByteBuf::writeBlockPos);
        buf.writeBoolean(editable);
        buf.writeComponent(feedback);
    }
    static HardpointView read(FriendlyByteBuf b) {
        return new HardpointView(b.readUUID(), b.readUtf(100), b.readEnum(HardpointEditor.Field.class), b.readVarInt(),
                b.readUtf(100), b.readList(buf -> buf.readUtf(32)), b.readVarInt(), b.readVarInt(), b.readBoolean(),
                b.readOptional(FriendlyByteBuf::readBlockPos), b.readOptional(FriendlyByteBuf::readBlockPos),
                b.readBoolean(), b.readComponent());
    }
    boolean pending() { return first.isPresent() || second.isPresent(); }
}
