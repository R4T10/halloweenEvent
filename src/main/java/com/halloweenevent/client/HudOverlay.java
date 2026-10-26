package com.halloweenevent.client;

import com.halloweenevent.HalloweenEventMod;
import com.halloweenevent.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.gui.overlay.ForgeGui;

/** Candy counter in the top-left corner, drawn on a small textured frame. */
public final class HudOverlay {
    private static final ResourceLocation TEX = new ResourceLocation(HalloweenEventMod.MODID, "textures/gui/hud.png");
    private static final int FRAME_W = 104;
    private static final int FRAME_H = 26;
    private static ItemStack icon;

    private HudOverlay() {}

    public static void render(ForgeGui gui, GuiGraphics g, float partialTick, int w, int h) {
        Minecraft mc = Minecraft.getInstance();
        if (!ClientState.active || mc.options.hideGui || mc.player == null) return;
        if (!ClientState.inZone && !ClientState.completed && ClientState.count == 0) return;
        if (icon == null) icon = new ItemStack(ModItems.CANDY_CORN.get());

        int x = 4, y = 4;
        g.blit(TEX, x, y, 0, 0, FRAME_W, FRAME_H, 128, 32);
        g.renderItem(icon, x + 5, y + 5);

        Component count = Component.translatable("hud.halloweenevent.count", ClientState.count, ClientState.target);
        g.drawString(mc.font, count, x + 25, y + 4, 0xFFFFFF, true);

        Component hint;
        if (ClientState.completed) {
            hint = Component.translatable("hud.halloweenevent.done").withStyle(ChatFormatting.GREEN);
        } else if (ClientState.count >= ClientState.target) {
            hint = Component.translatable("hud.halloweenevent.escape").withStyle(ChatFormatting.YELLOW);
        } else {
            hint = Component.translatable("hud.halloweenevent.hunt").withStyle(ChatFormatting.GRAY);
        }
        g.drawString(mc.font, hint, x + 25, y + 14, 0xFFFFFF, true);
    }
}
