package com.halloweenevent.client.screen;

import com.halloweenevent.HalloweenEventMod;
import com.halloweenevent.network.AdminAction;
import com.halloweenevent.network.AdminSnapshot;
import com.halloweenevent.network.C2SAdminAction;
import com.halloweenevent.network.Net;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Operator panel opened by {@code /halloween admin}. Pure view: every button sends a
 * {@link C2SAdminAction}; the server validates it and answers with a fresh snapshot.
 */
public class AdminScreen extends Screen {
    private static final ResourceLocation TEX = new ResourceLocation(HalloweenEventMod.MODID, "textures/gui/admin.png");
    private static final int W = 248;
    private static final int H = 200;
    private static final int LABEL = 0x404040;
    private static final int ROWS = 5;

    private enum Tab { EVENT, CANDY, PLAYERS, SETTINGS, HORROR }

    private record Label(Component text, int x, int y, boolean centered) {}

    /** Remembered between openings, like vanilla's last-used tab. */
    private static Tab tab = Tab.EVENT;

    private final List<Label> labels = new ArrayList<>();
    private AdminSnapshot snap;
    private EditBox commandBox;
    private int left;
    private int top;
    private int page;

    public AdminScreen(AdminSnapshot snapshot) {
        super(Component.translatable("gui.halloweenevent.admin.title"));
        this.snap = snapshot;
    }

    public void update(AdminSnapshot snapshot) {
        String draft = commandBox != null && commandBox.isFocused() ? commandBox.getValue() : null;
        this.snap = snapshot;
        rebuildWidgets();
        if (draft != null && commandBox != null) {
            commandBox.setValue(draft);
            setFocused(commandBox);
        }
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        labels.clear();
        commandBox = null;

        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            Tab t = tabs[i];
            Button b = addRenderableWidget(Button.builder(
                            Component.translatable("gui.halloweenevent.tab." + t.name().toLowerCase(Locale.ROOT)),
                            btn -> {
                                tab = t;
                                page = 0;
                                rebuildWidgets();
                            })
                    .bounds(left + 7 + i * 47, top + 18, 45, 20).build());
            b.active = tab != t;
        }
        switch (tab) {
            case EVENT -> initEvent();
            case CANDY -> initCandy();
            case PLAYERS -> initPlayers();
            case SETTINGS -> initSettings();
            case HORROR -> initHorror();
        }
    }

    // ---- tabs ----

    private void initEvent() {
        int y = top + 46;
        label(Component.translatable("gui.halloweenevent.status",
                Component.translatable(snap.active ? "gui.halloweenevent.running" : "gui.halloweenevent.stopped")
                        .withStyle(snap.active ? ChatFormatting.DARK_GREEN : ChatFormatting.DARK_RED)), left + 8, y);
        Component zone = snap.corner1 != null && snap.corner2 != null
                ? Component.translatable("gui.halloweenevent.zone", snap.corner1.toShortString(), snap.corner2.toShortString())
                : Component.translatable("gui.halloweenevent.zone_unset",
                        snap.corner1 == null ? "-" : snap.corner1.toShortString(),
                        snap.corner2 == null ? "-" : snap.corner2.toShortString());
        label(zone, left + 8, y + 11);
        label(Component.translatable("gui.halloweenevent.dimension", snap.dimension), left + 8, y + 22);
        label(Component.translatable("gui.halloweenevent.integrations",
                found(snap.holdHandsLoaded), found(snap.carryOnLoaded)), left + 8, y + 33);

        addRenderableWidget(button("gui.halloweenevent.start", AdminAction.START, left + 8, top + 96, 114)).active = !snap.active;
        addRenderableWidget(button("gui.halloweenevent.stop", AdminAction.STOP, left + 126, top + 96, 114)).active = snap.active;
        addRenderableWidget(button("gui.halloweenevent.corner1", AdminAction.CORNER_1, left + 8, top + 120, 114))
                .setTooltip(Tooltip.create(Component.translatable("gui.halloweenevent.corner.tip")));
        addRenderableWidget(button("gui.halloweenevent.corner2", AdminAction.CORNER_2, left + 126, top + 120, 114))
                .setTooltip(Tooltip.create(Component.translatable("gui.halloweenevent.corner.tip")));
        addRenderableWidget(confirmButton("gui.halloweenevent.clear_zone", "gui.halloweenevent.confirm.clear_zone",
                AdminAction.CLEAR_ZONE, left + 8, top + 144, 114));
        addRenderableWidget(confirmButton("gui.halloweenevent.reset_progress", "gui.halloweenevent.confirm.reset_progress",
                AdminAction.RESET_PROGRESS, left + 126, top + 144, 114));
        addRenderableWidget(button("gui.halloweenevent.refresh", AdminAction.REFRESH, left + 8, top + 168, 232));
    }

    private void initCandy() {
        label(Component.translatable("gui.halloweenevent.spawns", snap.spawnCount), left + 8, top + 46);
        addRenderableWidget(button("gui.halloweenevent.add_candy", AdminAction.ADD_CANDY, left + 8, top + 58, 114))
                .setTooltip(Tooltip.create(Component.translatable("gui.halloweenevent.add_candy.tip")));
        addRenderableWidget(button("gui.halloweenevent.remove_candy", AdminAction.REMOVE_NEAREST_CANDY, left + 126, top + 58, 114))
                .setTooltip(Tooltip.create(Component.translatable("gui.halloweenevent.remove_candy.tip")));
        addRenderableWidget(Button.builder(Component.translatable("gui.halloweenevent.mode",
                                Component.translatable(snap.sharedMode ? "gui.halloweenevent.mode.shared" : "gui.halloweenevent.mode.per_player")),
                        b -> send(AdminAction.TOGGLE_MODE, 0, ""))
                .tooltip(Tooltip.create(Component.translatable(snap.sharedMode
                        ? "gui.halloweenevent.mode.shared.tip" : "gui.halloweenevent.mode.per_player.tip")))
                .bounds(left + 8, top + 82, 114, 20).build());
        addRenderableWidget(confirmButton("gui.halloweenevent.clear_candies", "gui.halloweenevent.confirm.clear_candies",
                AdminAction.CLEAR_CANDIES, left + 126, top + 82, 114));

        stepper(top + 108, Component.translatable("gui.halloweenevent.target"), Component.literal(String.valueOf(snap.target)),
                AdminAction.SET_TARGET, snap.target, new int[]{-5, -1, 1, 5}, true);
        stepper(top + 132, Component.translatable("gui.halloweenevent.respawn"),
                Component.translatable("gui.halloweenevent.seconds", snap.respawnSeconds),
                AdminAction.SET_RESPAWN, snap.respawnSeconds, new int[]{-30, -5, 5, 30}, snap.sharedMode);

        if (!snap.sharedMode && snap.spawnCount < snap.target) {
            label(Component.translatable("gui.halloweenevent.not_enough", snap.spawnCount, snap.target)
                    .withStyle(ChatFormatting.DARK_RED), left + 8, top + 162);
        }
    }

    private void initPlayers() {
        label(Component.translatable("gui.halloweenevent.col.player"), left + 8, top + 46);
        label(Component.translatable("gui.halloweenevent.col.candies"), left + 100, top + 46);
        label(Component.translatable("gui.halloweenevent.col.status"), left + 142, top + 46);
        label(Component.translatable("gui.halloweenevent.col.scare"), left + 200, top + 46);

        int pages = Math.max(1, (snap.players.size() + ROWS - 1) / ROWS);
        page = Math.min(page, pages - 1);
        if (snap.players.isEmpty()) {
            label(Component.translatable("gui.halloweenevent.no_players"), left + 8, top + 62);
        }
        for (int i = 0; i < ROWS; i++) {
            int index = page * ROWS + i;
            if (index >= snap.players.size()) break;
            AdminSnapshot.PlayerRow row = snap.players.get(index);
            int y = top + 58 + i * 22;
            label(Component.literal(font.plainSubstrByWidth(row.name(), 88)), left + 8, y + 6);
            label(Component.literal(row.count() + "/" + snap.target), left + 100, y + 6);
            label(status(row), left + 142, y + 6);
            int next = row.level() % 3 + 1;
            addRenderableWidget(Button.builder(Component.translatable("gui.halloweenevent.level", row.level()),
                            b -> send(AdminAction.SET_PLAYER_LEVEL, next, row.id().toString()))
                    .tooltip(Tooltip.create(Component.translatable("gui.halloweenevent.level.tip." + row.level())))
                    .bounds(left + 196, y, 44, 20).build());
        }

        int y = top + 172;
        addRenderableWidget(Button.builder(Component.literal("<"), b -> turnPage(-1)).bounds(left + 8, y, 20, 20).build()).active = page > 0;
        addRenderableWidget(Button.builder(Component.literal(">"), b -> turnPage(1)).bounds(left + 30, y, 20, 20).build()).active = page < pages - 1;
        label(Component.translatable("gui.halloweenevent.page", page + 1, pages), left + 56, y + 6);
        int next = snap.defaultLevel % 3 + 1;
        addRenderableWidget(Button.builder(Component.translatable("gui.halloweenevent.default_level", snap.defaultLevel),
                        b -> send(AdminAction.SET_DEFAULT_LEVEL, next, ""))
                .tooltip(Tooltip.create(Component.translatable("gui.halloweenevent.default_level.tip")))
                .bounds(left + 120, y, 120, 20).build());
    }

    private void initHorror() {
        stepper(top + 46, Component.translatable("gui.halloweenevent.grab_interval"),
                snap.grabIntervalSeconds == 0 ? Component.translatable("options.off")
                        : Component.translatable("gui.halloweenevent.seconds", snap.grabIntervalSeconds),
                AdminAction.SET_GRAB_INTERVAL, snap.grabIntervalSeconds, new int[]{-30, -10, 10, 30}, snap.holdHandsLoaded);
        label(Component.translatable("gui.halloweenevent.grab_info1"), left + 8, top + 74);
        label(Component.translatable("gui.halloweenevent.grab_info2"), left + 8, top + 86);
        label(Component.translatable("gui.halloweenevent.grab_info3"), left + 8, top + 98);
        if (!snap.holdHandsLoaded) {
            label(Component.translatable("gui.halloweenevent.grab_needs").withStyle(ChatFormatting.DARK_RED), left + 8, top + 114);
        }
    }

    private void initSettings() {
        label(Component.translatable("gui.halloweenevent.reward"), left + 30, top + 51);
        addRenderableWidget(button("gui.halloweenevent.reward_from_hand", AdminAction.REWARD_FROM_HAND, left + 126, top + 45, 114));

        commandBox = new EditBox(font, left + 9, top + 71, 178, 18, Component.translatable("gui.halloweenevent.reward_command"));
        commandBox.setMaxLength(256);
        commandBox.setValue(snap.rewardCommand);
        commandBox.setHint(Component.translatable("gui.halloweenevent.reward_command.hint").withStyle(ChatFormatting.DARK_GRAY));
        commandBox.setTooltip(Tooltip.create(Component.translatable("gui.halloweenevent.reward_command.tip")));
        addRenderableWidget(commandBox);
        addRenderableWidget(Button.builder(Component.translatable("gui.halloweenevent.save"),
                b -> send(AdminAction.SET_REWARD_COMMAND, 0, commandBox.getValue())).bounds(left + 192, top + 70, 48, 20).build());

        addRenderableWidget(toggle("gui.halloweenevent.hold_scares", snap.holdScares, AdminAction.TOGGLE_HOLD_SCARES, top + 94))
                .active = snap.holdHandsLoaded;
        addRenderableWidget(toggle("gui.halloweenevent.prevent_carry", snap.preventCarry, AdminAction.TOGGLE_PREVENT_CARRY, top + 118))
                .active = snap.carryOnLoaded;
        stepper(top + 142, Component.translatable("gui.halloweenevent.carry_drop"),
                snap.carryDropSeconds == 0 ? Component.translatable("options.off") : Component.translatable("gui.halloweenevent.seconds", snap.carryDropSeconds),
                AdminAction.SET_CARRY_DROP, snap.carryDropSeconds, new int[]{-5, -1, 1, 5}, snap.carryOnLoaded);
        addRenderableWidget(toggle("gui.halloweenevent.freecam_guard", snap.freecamGuard, AdminAction.TOGGLE_FREECAM_GUARD, top + 166));
    }

    // ---- widgets ----

    private void label(Component text, int x, int y) {
        labels.add(new Label(text, x, y, false));
    }

    private Button button(String key, int action, int x, int y, int w) {
        return Button.builder(Component.translatable(key), b -> send(action, 0, "")).bounds(x, y, w, 20).build();
    }

    private Button confirmButton(String key, String question, int action, int x, int y, int w) {
        return Button.builder(Component.translatable(key), b -> minecraft.setScreen(new ConfirmScreen(ok -> {
            if (ok) send(action, 0, "");
            minecraft.setScreen(this);
        }, Component.translatable(key), Component.translatable(question)))).bounds(x, y, w, 20).build();
    }

    private Button toggle(String key, boolean value, int action, int y) {
        return Button.builder(Component.translatable(key, Component.translatable(value ? "options.on" : "options.off")),
                b -> send(action, 0, "")).bounds(left + 8, y, 232, 20).build();
    }

    /** Label on the left, then [-a][-b] value [+c][+d] on the right. */
    private void stepper(int y, Component name, Component value, int action, int current, int[] deltas, boolean enabled) {
        label(name, left + 8, y + 6);
        int[] xs = {left + 110, left + 134, left + 196, left + 220};
        for (int i = 0; i < deltas.length; i++) {
            int delta = deltas[i];
            addRenderableWidget(Button.builder(Component.literal(delta > 0 ? "+" + delta : String.valueOf(delta)),
                    b -> send(action, current + delta, "")).bounds(xs[i], y, 22, 20).build()).active = enabled;
        }
        labels.add(new Label(value, left + 176, y + 6, true));
    }

    private Component found(boolean loaded) {
        return Component.translatable(loaded ? "gui.halloweenevent.found" : "gui.halloweenevent.missing")
                .withStyle(loaded ? ChatFormatting.DARK_GREEN : ChatFormatting.DARK_RED);
    }

    private static Component status(AdminSnapshot.PlayerRow row) {
        MutableComponent c;
        if (row.freecam()) c = Component.translatable("gui.halloweenevent.state.freecam").withStyle(ChatFormatting.DARK_RED);
        else if (row.completed()) c = Component.translatable("gui.halloweenevent.state.escaped").withStyle(ChatFormatting.DARK_GREEN);
        else if (row.inZone()) c = Component.translatable("gui.halloweenevent.state.in_zone");
        else if (row.online()) c = Component.translatable("gui.halloweenevent.state.outside");
        else c = Component.translatable("gui.halloweenevent.state.offline").withStyle(ChatFormatting.DARK_GRAY);
        return c;
    }

    private void turnPage(int delta) {
        page += delta;
        rebuildWidgets();
    }

    private static void send(int action, int value, String text) {
        Net.toServer(new C2SAdminAction(action, value, text));
    }

    // ---- screen ----

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (tab == Tab.PLAYERS) {
            int pages = Math.max(1, (snap.players.size() + ROWS - 1) / ROWS);
            int next = Math.max(0, Math.min(pages - 1, page - (int) Math.signum(delta)));
            if (next != page) {
                page = next;
                rebuildWidgets();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        g.blit(TEX, left, top, 0, 0, W, H);
        g.drawString(font, title, left + 8, top + 6, LABEL, false);
        for (Label l : labels) {
            int x = l.centered() ? l.x() - font.width(l.text()) / 2 : l.x();
            g.drawString(font, l.text(), x, l.y(), LABEL, false);
        }
        boolean rewardSlot = tab == Tab.SETTINGS;
        if (rewardSlot) {
            g.blit(TEX, left + 8, top + 46, 0, 204, 18, 18);
            g.renderItem(snap.reward, left + 9, top + 47);
            g.renderItemDecorations(font, snap.reward, left + 9, top + 47);
        }
        super.render(g, mouseX, mouseY, partialTick);
        if (rewardSlot && !snap.reward.isEmpty()
                && mouseX >= left + 8 && mouseX < left + 26 && mouseY >= top + 46 && mouseY < top + 64) {
            g.renderTooltip(font, snap.reward, mouseX, mouseY);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
