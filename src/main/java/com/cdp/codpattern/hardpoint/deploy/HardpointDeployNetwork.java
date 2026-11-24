package com.cdp.codpattern.hardpoint.deploy;

import com.cdp.codpattern.hardpoint.HardpointAddon;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import java.util.UUID;
import java.util.function.Supplier;

final class HardpointDeployNetwork {
    private static final String VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(HardpointAddon.MOD_ID, "deployment"),
            () -> VERSION, VERSION::equals, VERSION::equals);
    private HardpointDeployNetwork() {}
    static void register() {
        CHANNEL.messageBuilder(Request.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder(Request::write).decoder(Request::read).consumerMainThread(Request::handle).add();
        CHANNEL.messageBuilder(Response.class, 1, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(Response::write).decoder(Response::read).consumerMainThread(Response::handle).add();
    }
    static void send(UUID session, HardpointEditor.Action action, String value) {
        CHANNEL.sendToServer(new Request(session, action, value));
    }
    static void show(ServerPlayer player, HardpointView view, boolean open) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new Response(view, open));
    }
    private record Request(UUID session, HardpointEditor.Action action, String value) {
        void write(FriendlyByteBuf b) { b.writeUUID(session); b.writeEnum(action); b.writeUtf(value, 100); }
        static Request read(FriendlyByteBuf b) {
            return new Request(b.readUUID(), b.readEnum(HardpointEditor.Action.class), b.readUtf(100));
        }
        void handle(Supplier<NetworkEvent.Context> ctx) {
            var p = ctx.get().getSender();
            if (p != null) show(p, HardpointEditor.act(p, session, action, value), false);
        }
    }
    private record Response(HardpointView view, boolean open) {
        void write(FriendlyByteBuf b) {
            b.writeBoolean(view != null);
            if (view != null) view.write(b);
            b.writeBoolean(open);
        }
        static Response read(FriendlyByteBuf b) {
            return new Response(b.readBoolean() ? HardpointView.read(b) : null, b.readBoolean());
        }
        void handle(Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> HardpointDeployScreen.receive(view, open));
        }
    }
}
