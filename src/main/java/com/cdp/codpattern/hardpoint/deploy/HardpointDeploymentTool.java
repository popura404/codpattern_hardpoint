package com.cdp.codpattern.hardpoint.deploy;

import com.cdp.codpattern.hardpoint.HardpointAddon;
import com.phasetranscrystal.fpsmatch.common.item.FPSMCreativeModeTabRegister;
import com.phasetranscrystal.fpsmatch.common.item.tool.CreatorToolItem;
import com.phasetranscrystal.fpsmatch.common.item.tool.ToolAccessHelper;
import com.phasetranscrystal.fpsmatch.common.item.tool.ToolInteractionAction;
import com.phasetranscrystal.fpsmatch.common.item.tool.ToolInteractionHit;
import com.phasetranscrystal.fpsmatch.common.item.tool.WorldToolItem;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.List;

public final class HardpointDeploymentTool extends CreatorToolItem implements WorldToolItem {
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, HardpointAddon.MOD_ID);
    public static final RegistryObject<HardpointDeploymentTool> ITEM = ITEMS.register("deployment_tool", HardpointDeploymentTool::new);

    private HardpointDeploymentTool() { super(new Properties().stacksTo(1)); }

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
        bus.addListener(HardpointDeploymentTool::addCreativeItem);
        HardpointDeployNetwork.register();
    }

    private static void addCreativeItem(BuildCreativeModeTabContentsEvent event) {
        if (FPSMCreativeModeTabRegister.CODPATTERN_TOOLS_AND_ITEMS_KEY.equals(event.getTabKey())) {
            event.accept(ITEM);
        }
    }

    @Override
    public void handleWorldInteraction(ServerPlayer player, ItemStack stack, ToolInteractionAction action, ToolInteractionHit hit) {
        if (stack != player.getMainHandItem() || !ToolAccessHelper.ensureAdminAccess(player)) return;
        if (action == ToolInteractionAction.CTRL_RIGHT_CLICK) {
            HardpointDeployNetwork.show(player, HardpointEditor.open(player), true);
        } else if (hit != null) {
            HardpointEditor.capture(player, action, hit);
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("deploy.codpattern_hardpoint.hint").withStyle(ChatFormatting.LIGHT_PURPLE));
    }
}

