package com.cdp.codpattern.hardpoint.room;

import com.cdp.codpattern.core.throwable.ThrowableItemHelper;
import com.cdp.codpattern.hardpoint.HardpointAddon;
import com.cdp.codpattern.hardpoint.client.HardpointView;
import com.phasetranscrystal.fpsmatch.core.FPSMCore;
import com.tacz.guns.api.event.common.GunFireEvent;
import com.tacz.guns.api.event.common.GunShootEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.*;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;

/** Stop firing before consuming ammunition or launching a projectile, on both logical sides. */
@Mod.EventBusSubscriber(modid = HardpointAddon.MOD_ID)
public final class HardpointWarmupWeapons {
    private HardpointWarmupWeapons() {}

    public static boolean blocked(Entity actor) {
        if (actor instanceof ServerPlayer player) {
            return FPSMCore.initialized() && FPSMCore.getInstance().getMapByPlayerWithSpec(player).orElse(null)
                    instanceof HardpointMap room && room.isWarmingUp();
        }
        return actor instanceof Player && actor.level().isClientSide() && Boolean.TRUE.equals(
                DistExecutor.unsafeCallWhenOn(Dist.CLIENT, () -> () -> HardpointView.preparingLocalPlayer(actor)));
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void shoot(GunShootEvent event) {
        if (blocked(event.getShooter())) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void fire(GunFireEvent event) {
        // TaCZ posts this per scheduled burst shot, before ammo, heat, sound and bullet creation.
        if (blocked(event.getShooter())) event.setCanceled(true);
    }

    private static boolean launchable(ItemStack stack) {
        Item item = stack.getItem();
        return item instanceof SnowballItem || item instanceof EggItem || item instanceof EnderpearlItem
                || item instanceof ExperienceBottleItem || item instanceof ThrowablePotionItem
                || item instanceof ProjectileWeaponItem || item instanceof TridentItem || item instanceof FishingRodItem
                || item instanceof FireworkRocketItem || item instanceof EnderEyeItem || ThrowableItemHelper.isThrowableStack(stack);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void useItem(PlayerInteractEvent.RightClickItem event) {
        if (blocked(event.getEntity()) && launchable(event.getItemStack())) {
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void useOnBlock(PlayerInteractEvent.RightClickBlock event) {
        if (blocked(event.getEntity()) && launchable(event.getItemStack())) event.setUseItem(Event.Result.DENY);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void startUsing(LivingEntityUseItemEvent.Start event) {
        if (blocked(event.getEntity()) && launchable(event.getItem())) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void release(LivingEntityUseItemEvent.Stop event) {
        if (blocked(event.getEntity()) && launchable(event.getItem())) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void projectile(EntityJoinLevelEvent event) {
        if (!event.loadedFromDisk() && event.getEntity() instanceof Projectile shot && blocked(shot.getOwner())) {
            event.setCanceled(true);
        }
    }
}
