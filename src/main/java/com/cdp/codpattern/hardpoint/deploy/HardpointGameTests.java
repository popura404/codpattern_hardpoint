package com.cdp.codpattern.hardpoint.deploy;

import com.cdp.codpattern.app.match.GameModeRegistry;
import com.cdp.codpattern.app.match.model.DeathContext;
import com.cdp.codpattern.app.match.model.ModeRoomTickContext;
import com.cdp.codpattern.app.match.model.ModeRuntimeStateSnapshot;
import com.cdp.codpattern.app.match.port.ModeRoomReadPort;
import com.cdp.codpattern.app.match.service.ModeRoomInteractionService;
import com.cdp.codpattern.app.refit.service.AttachmentPresetRequestService;
import com.cdp.codpattern.app.tdm.service.TdmSettlementTiming;
import com.cdp.codpattern.config.backpack.BackpackConfig;
import com.cdp.codpattern.config.backpack.BackpackConfigRepository;
import com.cdp.codpattern.config.path.ConfigPath;
import com.cdp.codpattern.config.storage.ServerMapStorage;
import com.cdp.codpattern.hardpoint.HardpointAddon;
import com.cdp.codpattern.hardpoint.room.HardpointMap;
import com.cdp.codpattern.hardpoint.storage.HardpointStorage;
import com.mojang.authlib.GameProfile;
import com.phasetranscrystal.fpsmatch.common.item.tool.ToolInteractionAction;
import com.phasetranscrystal.fpsmatch.common.item.tool.ToolInteractionHit;
import com.phasetranscrystal.fpsmatch.core.FPSMCore;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.entity.ShootResult;
import com.tacz.guns.api.event.common.GunFireEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@GameTestHolder(HardpointAddon.MOD_ID)
@PrefixGameTestTemplate(false)
public final class HardpointGameTests {
    private HardpointGameTests() { }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void deployReorderReloadAndPlay(GameTestHelper test) throws Exception {
        var level = test.getLevel();
        test.assertTrue(level.getServer() instanceof GameTestServer, "Run on an isolated GameTest server");
        var core = FPSMCore.getInstance();
        String mode = HardpointAddon.GAME_TYPE;
        test.assertTrue(GameModeRegistry.canonicalize("hp").equals(mode), "The lobby must discover Hardpoint");
        String name = "hp-test-" + UUID.randomUUID().toString().substring(0, 8);
        BlockPos home = test.absolutePos(new BlockPos(0, 8, 0));
        for (int x = -2; x <= 9; x++) {
            for (int z = -2; z <= 8; z++) {
                var feet = home.offset(x, 0, z);
                level.setBlockAndUpdate(feet.below(), Blocks.STONE.defaultBlockState());
                for (int y = 0; y <= 3; y++) level.setBlockAndUpdate(feet.above(y), Blocks.AIR.defaultBlockState());
            }
        }
        var red = player(level, "hp-red", true);
        var blue = player(level, "hp-blue", false);
        var online = onlinePlayers(level.getServer().getPlayerList());
        var file = ServerMapStorage.get(level.getServer()).paths().map(HardpointStorage.DIRECTORY, name).resolve("map.json");
        try {
            var tool = new ItemStack(HardpointDeploymentTool.ITEM.get());
            red.setItemInHand(InteractionHand.MAIN_HAND, tool);
            blue.setItemInHand(InteractionHand.MAIN_HAND, tool.copy());
            test.assertTrue(HardpointEditor.open(blue) == null, "Map editing requires operator permission");
            mark(red, home.offset(-1, -1, -1), false);
            mark(red, home.offset(8, 3, 7), true);
            var view = act(red, HardpointEditor.Action.CREATE, name);
            test.assertTrue(name.equals(view.mapName()), "Create from the two marked map corners: " + view.feedback().getString());
            BlockPos first = home.offset(2, 0, 4), second = home.offset(6, 0, 4);
            draw(red, first, "Courtyard");
            act(red, HardpointEditor.Action.NEXT_ZONE, "");
            draw(red, second, "Bridge");
            view = act(red, HardpointEditor.Action.UP, "");
            test.assertTrue(view.zones().equals(List.of("Bridge", "Courtyard")), "Moving a zone must change rotation order");
            setSpawn(red, "RED", home.offset(1, 0, 1));
            setSpawn(red, "BLUE", home.offset(6, 0, 1));
            var original = (HardpointMap) core.getMapByTypeWithName(mode, name).orElseThrow();
            test.assertTrue(original.deployment().problems(original).isEmpty(), "Drawn map must pass deployment checks");
            core.unregisterMap(original);
            HardpointStorage.INSTANCE.retire(original);
            core.getFPSMDataManager().readData();
            var map = (HardpointMap) core.getMapByTypeWithName(mode, name).orElseThrow();
            test.assertTrue(map != original && map.deployment().equals(original.deployment()), "Saved corners and order must survive reload");

            red.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            blue.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            move(red, home);
            move(blue, home.offset(0, 0, 1));
            for (var p : List.of(red, blue)) {
                online.put(p.getUUID(), p);
                level.addNewPlayer(p);
                var bags = BackpackConfigRepository.loadOrCreatePlayer(p.getUUID().toString(), ConfigPath.SERVERBACKPACK.getPath(p.server));
                var bag = new BackpackConfig.Backpack("Hardpoint test");
                bag.setItem_MAP("primary", BackpackConfig.itemDataP2);
                bags.getBackpacks_MAP().put(1, bag);
                bags.setSelectedBackpack(1);
                test.assertTrue(ModeRoomInteractionService.joinRoom(p, map.roomId().encode(), p == red ? "red" : "blue").success(), "Lobby admission");
                test.assertTrue(map.roomHandle().readyPort().orElseThrow().setPlayerReady(p, true), "Ready control");
            }
            BackpackConfigRepository.save();
            var votes = map.roomHandle().votePort().orElseThrow();
            test.assertTrue(votes.initiateStartVote(red.getUUID()), "Start through the normal vote");
            long voteId = map.activeStartVoteId().orElseThrow();
            for (var p : List.of(red, blue)) votes.submitVoteResponse(p.getUUID(), voteId, true);
            var ticks = new MatchTicks(map);
            var room = (ModeRoomReadPort) map.roomHandle().summaryPort();
            test.assertTrue("COUNTDOWN".equals(ticks.phase(red)) && ticks.snapshot(red).remainingTimeTicks() == 200
                    && !ticks.snapshot(red).playerValues().containsKey("nextIndex"), "Vote starts the lobby countdown");
            ticks.advance(199);
            test.assertTrue(red.blockPosition().equals(home) && red.getInventory().getItem(0).isEmpty()
                    && red.getAttributeValue(Attributes.MOVEMENT_SPEED) > 0 && !room.canDealDamage(),
                    "Stay in the lobby without a kit or movement lock until the countdown ends");
            ticks.advance(1);
            test.assertTrue("WARMUP".equals(ticks.phase(red)) && ticks.snapshot(red).remainingTimeTicks() == 400
                    && red.blockPosition().equals(home.offset(1, 0, 1)) && blue.blockPosition().equals(home.offset(6, 0, 1)),
                    "At blackout move both teams to their own spawns");
            test.assertTrue("tacz:ak47".equals(red.getInventory().getItem(0).getOrCreateTag().getString("GunId")),
                    "CDP must issue the actual selected primary");
            var gun = red.getInventory().getItem(0);
            var before = gun.copy();
            var shot = IGunOperator.fromLivingEntity(red).shoot(red::getXRot, red::getYRot, 0L);
            test.assertTrue(shot == ShootResult.FORGE_EVENT_CANCEL && ItemStack.matches(gun, before),
                    "Preparation must prevent firing the issued gun before its ammo changes");
            assertCombatActions(test, red, true);
            ticks.advance(399);
            test.assertTrue(red.getAttributeValue(Attributes.MOVEMENT_SPEED) == 0 && !room.canDealDamage()
                    && ticks.score(red) == 0, "Preparation keeps movement and combat locked");
            ticks.advance(1);
            test.assertTrue("PLAYING".equals(ticks.phase(red)) && red.getAttributeValue(Attributes.MOVEMENT_SPEED) > 0
                    && room.canDealDamage(), "Movement and combat unlock together at match start");
            assertCombatActions(test, red, false);
            move(red, second);
            ticks.advance(99);
            test.assertTrue(!ticks.snapshot(red).playerValues().containsKey("pointIndex")
                    && ticks.snapshot(red).playerValues().get("nextIndex").value().equals("1") && ticks.score(red) == 0,
                    "Players can approach P1 for five seconds before scoring");
            ticks.advance(1);
            test.assertTrue(ticks.snapshot(red).playerValues().get("pointIndex").value().equals("1")
                    && ticks.snapshot(red).remainingTimeTicks() == 5900
                    && ticks.snapshot(red).playerValues().get("pointRemainingTicks").value().equals("1200"),
                    "P1 opens with its own full sixty seconds");
            test.assertTrue(AttachmentPresetRequestService.prepare(red, UUID.randomUUID(), 1, "primary").isEmpty(),
                    "Opening a CDP refit preview in combat must not replace live equipment");
            move(red, second);
            ticks.advance(40);
            test.assertTrue(ticks.score(red) == 2, "First hill is the one moved to the top");
            move(blue, second.offset(1, 0, 0));
            ticks.advance(40);
            test.assertTrue(ticks.score(red) == 2 && ticks.score(blue) == 0, "Both teams in the zone must stop scoring");
            move(blue, home);
            ticks.advance(1120);
            test.assertTrue(ticks.score(red) == 58, "Contested seconds must count towards the sixty-second rotation");
            ticks.advance(20);
            test.assertTrue(ticks.score(red) == 58, "Standing on the old hill must stop scoring");
            var redBags = BackpackConfigRepository.loadOrCreatePlayer(red.getUUID().toString(), ConfigPath.SERVERBACKPACK.getPath(red.server));
            var nextBag = new BackpackConfig.Backpack("Next spawn");
            nextBag.setItem_MAP("primary", BackpackConfig.itemDataP3);
            redBags.getBackpacks_MAP().put(2, nextBag);
            redBags.setSelectedBackpack(2);
            BackpackConfigRepository.save();
            map.roomHandle().kitDistributionPort().orElseThrow().onBackpackSelected(red, 2);
            var death = new DeathContext(red.damageSources().playerAttack(blue), blue, Optional.of(blue));
            map.roomHandle().combatEventPort().orElseThrow().onPlayerDeath(red, death);
            move(red, first);
            ticks.advance(59);
            test.assertTrue(red.isSpectator() && ticks.score(red) == 58 && ticks.score(blue) == 0,
                    "Elimination and a spectating player on the point must award no objective points");
            ticks.advance(1);
            test.assertTrue(!red.isSpectator() && "tacz:m4a1".equals(red.getInventory().getItem(0).getOrCreateTag().getString("GunId")),
                    "On respawn the room must issue the newly selected CDP backpack");
            // Finish through the real map tick port using the configured, cyclic order.
            for (int tick = 0; tick < 4000 && ticks.phase(red).equals("PLAYING"); tick++) {
                var snapshot = map.roomHandle().runtimeStatePort().orElseThrow().runtimeStateSnapshot(red);
                move(red, snapshot.playerValues().get("pointName").value().equals("Courtyard") ? first : second);
                ticks.advance(1);
            }
            test.assertTrue("ENDED".equals(ticks.phase(red)) && ticks.score(red) == 250 && ticks.score(blue) == 0,
                    "Reaching 250 must freeze the final match score");
            var finalValues = map.roomHandle().runtimeStatePort().orElseThrow().runtimeStateSnapshot(red).playerValues();
            test.assertTrue(finalValues.get("winner").value().equals("red")
                    && finalValues.get("hold." + red.getUUID()).value().equals("250"), "Actual winner and objective time in settlement");
            ticks.advance(TdmSettlementTiming.TOTAL_TICKS);
            test.assertTrue("WAITING".equals(ticks.phase(red)) && ticks.score(red) == 0, "Room must be ready for a rematch");
            for (var p : List.of(red, blue)) {
                test.assertTrue(ModeRoomInteractionService.joinRoom(p, map.roomId().encode(), p == red ? "red" : "blue").success(),
                        "Players can rejoin after the completed match");
                test.assertTrue(map.roomHandle().readyPort().orElseThrow().setPlayerReady(p, true), "Ready for a second match");
            }
            test.assertTrue(votes.initiateStartVote(red.getUUID()), "Vote again in the same room");
            long rematch = map.activeStartVoteId().orElseThrow();
            votes.submitVoteResponse(red.getUUID(), rematch, true);
            ticks.advance(1);
            test.assertTrue("START_VOTE".equals(ticks.phase(red)), "One vote must not start the countdown");
            votes.submitVoteResponse(blue.getUUID(), rematch, true);
            ticks.advance(200);
            test.assertTrue(ModeRoomInteractionService.leaveRoom(blue).success()
                    && blue.getAttributeValue(Attributes.MOVEMENT_SPEED) > 0, "Leaving during preparation restores movement");
            ticks.advance(1);
            test.assertTrue("ENDED".equals(ticks.phase(red)) && ticks.snapshot(red).playerValues().get("winner").value().equals("red")
                    && ticks.snapshot(red).playerValues().get("endReason").value().equals("forfeit")
                    && red.getAttributeValue(Attributes.MOVEMENT_SPEED) > 0 && ticks.score(red) == 0,
                    "A preparation forfeit must unlock the remaining team and preserve the tied scores");
            ticks.advance(TdmSettlementTiming.TOTAL_TICKS);
        } finally {
            for (var p : List.of(red, blue)) {
                if (core.getMapByPlayerWithSpec(p).isPresent()) ModeRoomInteractionService.leaveRoom(p);
                if (online.remove(p.getUUID()) != null) level.removePlayerImmediately(p, Entity.RemovalReason.DISCARDED);
            }
            core.getMapByTypeWithName(mode, name).ifPresent(map -> {
                core.unregisterMap(map);
                HardpointStorage.INSTANCE.retire(map);
            });
            Files.deleteIfExists(file);
            Files.deleteIfExists(file.getParent());
        }
        test.succeed();
    }

    private static void assertCombatActions(GameTestHelper test, ServerPlayer player, boolean preparing) {
        var fire = new GunFireEvent(player, player.getMainHandItem(), LogicalSide.SERVER);
        test.assertTrue(MinecraftForge.EVENT_BUS.post(fire) == preparing, "Queued TaCZ rounds must follow the phase");
        var original = player.getOffhandItem().copy();
        try {
            var eggs = new ItemStack(Items.EGG, 2);
            player.setItemInHand(InteractionHand.OFF_HAND, eggs);
            var used = player.gameMode.useItem(player, player.serverLevel(), eggs, InteractionHand.OFF_HAND);
            test.assertTrue(eggs.getCount() == (preparing ? 2 : 1) && used.consumesAction() != preparing,
                    "Preparing blocks a throw without consuming it, playing allows the same throw");
        } finally {
            player.setItemInHand(InteractionHand.OFF_HAND, original);
        }
    }

    private static void draw(ServerPlayer admin, BlockPos pos, String name) {
        mark(admin, pos.below(), false);
        mark(admin, pos.offset(1, -1, 1), true);
        act(admin, HardpointEditor.Action.SAVE, name);
    }

    private static void setSpawn(ServerPlayer admin, String team, BlockPos pos) {
        act(admin, HardpointEditor.Action.FIELD, team);
        mark(admin, pos.below(), false);
    }

    private static HardpointView act(ServerPlayer admin, HardpointEditor.Action action, String value) {
        return HardpointEditor.act(admin, HardpointEditor.open(admin).session(), action, value);
    }

    private static void mark(ServerPlayer admin, BlockPos clicked, boolean second) {
        move(admin, clicked.above());
        HardpointDeploymentTool.ITEM.get().handleWorldInteraction(admin, admin.getMainHandItem(),
                second ? ToolInteractionAction.RIGHT_CLICK_BLOCK : ToolInteractionAction.LEFT_CLICK_BLOCK,
                ToolInteractionHit.fromClicked(clicked, Direction.UP));
    }

    private static void move(ServerPlayer player, BlockPos feet) {
        player.moveTo(feet.getX() + .5, feet.getY(), feet.getZ() + .5, 0, 0);
    }

    private static final class MatchTicks {
        private final HardpointMap map;
        private long time;
        MatchTicks(HardpointMap map) { this.map = map; time = map.getServerLevel().getGameTime(); }
        void advance(int count) {
            var level = map.getServerLevel();
            for (int i = 0; i < count; i++) map.roomHandle().tickPort().orElseThrow().tick(
                    new ModeRoomTickContext(level.getServer(), level, map.roomId(), ++time));
        }
        ModeRuntimeStateSnapshot snapshot(ServerPlayer p) {
            return map.roomHandle().runtimeStatePort().orElseThrow().runtimeStateSnapshot(p);
        }
        String phase(ServerPlayer p) { return snapshot(p).phaseKey(); }
        int score(ServerPlayer p) {
            String key = p.getScoreboardName().equals("hp-red") ? "redScore" : "blueScore";
            return Integer.parseInt(map.roomHandle().runtimeStatePort().orElseThrow().runtimeStateSnapshot(p).playerValues().get(key).value());
        }
    }

    private static ServerPlayer player(ServerLevel level, String name, boolean admin) {
        var p = new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), name)) {
            @Override public boolean hasPermissions(int level) { return admin && level <= 2; }
        };
        var connection = new Connection(PacketFlow.SERVERBOUND) {
            @Override public void send(Packet<?> packet) { }
            @Override public void send(Packet<?> packet, PacketSendListener listener) { }
        };
        p.connection = new ServerGamePacketListenerImpl(level.getServer(), connection, p) {
            @Override public void teleport(double x, double y, double z, float yaw, float pitch) { p.moveTo(x, y, z, yaw, pitch); }
            @Override public void teleport(double x, double y, double z, float yaw, float pitch, Set<RelativeMovement> flags) {
                p.moveTo(x, y, z, yaw, pitch);
            }
        };
        return p;
    }

    /** Supply the same online UUID lookup CDP uses on a dedicated server without creating remote clients. */
    @SuppressWarnings("unchecked")
    private static Map<UUID, ServerPlayer> onlinePlayers(PlayerList list) throws IllegalAccessException {
        for (var field : PlayerList.class.getDeclaredFields()) {
            String type = field.getGenericType().getTypeName();
            if (Map.class.isAssignableFrom(field.getType()) && type.contains("java.util.UUID") && type.contains("ServerPlayer")) {
                field.setAccessible(true);
                return (Map<UUID, ServerPlayer>) field.get(list);
            }
        }
        throw new IllegalStateException("Missing online player lookup");
    }
}
