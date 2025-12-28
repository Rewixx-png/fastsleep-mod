package com.myname.fastsleep;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.GameRules;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.List;

@Mod("fastsleep")
public class FastSleep {

    private static final Logger LOGGER = LogUtils.getLogger();

    // === НАСТРОЙКИ ===
    private static final float BASE_TICK_RATE = 20.0f;
    private static final float MAX_TICK_RATE = 500.0f; 
    private static final float TARGET_MSPT_USAGE = 0.85f; 

    private boolean isFastSleepActive = false;
    private int originalSleepRule = 100;

    public FastSleep(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.register(this);
    }

    // Сброс при старте
    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        event.getServer().tickRateManager().setTickRate(BASE_TICK_RATE);
        isFastSleepActive = false;
    }

    // ЛОГИКА СЕРВЕРА
    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Pre event) {
        MinecraftServer server = event.getServer();
        if (server.getPlayerList().getPlayerCount() == 0) return;

        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        int total = players.size();
        int sleeping = 0;

        for (ServerPlayer player : players) {
            if (player.isSleeping()) sleeping++;
        }

        boolean shouldSpeedUp = total > 0 && ((float) sleeping / total) >= 0.5f;

        if (shouldSpeedUp) {
            if (!isFastSleepActive) {
                isFastSleepActive = true;
                GameRules rules = server.getGameRules();
                originalSleepRule = rules.getInt(GameRules.RULE_PLAYERS_SLEEPING_PERCENTAGE);
                rules.getRule(GameRules.RULE_PLAYERS_SLEEPING_PERCENTAGE).set(101, server);
            }

            float averageTickTimeMs = server.getAverageTickTimeNanos() / 1_000_000.0f;
            if (averageTickTimeMs < 0.5f) averageTickTimeMs = 0.5f;

            float theoreticalRate = (1000.0f / averageTickTimeMs) * TARGET_MSPT_USAGE;
            float targetRate = Mth.clamp(theoreticalRate, BASE_TICK_RATE, MAX_TICK_RATE);
            
            float currentRate = server.tickRateManager().tickrate();
            // Плавный разгон
            float smoothRate = Mth.lerp(0.1f, currentRate, targetRate); 

            server.tickRateManager().setTickRate(smoothRate);

        } else {
            if (isFastSleepActive) {
                isFastSleepActive = false;
                server.tickRateManager().setTickRate(BASE_TICK_RATE);
                server.getGameRules().getRule(GameRules.RULE_PLAYERS_SLEEPING_PERCENTAGE).set(originalSleepRule, server);
            }
            if (server.tickRateManager().tickrate() > 20.1f) {
                server.tickRateManager().setTickRate(BASE_TICK_RATE);
            }
        }
    }

    // ОТРИСОВКА (HUD)
    // RenderGuiEvent.Post рисует поверх всего, игнорируя скрытый интерфейс
    @SubscribeEvent
    public void onRenderGuiPost(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        boolean isSleeping = mc.player.isSleeping();
        float currentRate = mc.level.tickRateManager().tickrate();
        boolean isSpeeding = currentRate > 21.0f;

        if (isSleeping || isSpeeding) {
            var guiGraphics = event.getGuiGraphics();
            int width = guiGraphics.guiWidth();
            int height = guiGraphics.guiHeight();
            
            int centerX = width / 2;
            int yPos = height / 2 - 40; // Чуть выше центра, над кнопкой "Встать"

            // --- ВРЕМЯ ---
            long time = mc.level.getDayTime();
            long hour = ((time / 1000 + 6) % 24);
            long minute = (time % 1000) * 60 / 1000;
            String timeStr = String.format("%02d:%02d", hour, minute);
            
            // Рисуем обычным шрифтом, но жирным и ярким
            guiGraphics.drawCenteredString(mc.font, Component.literal("Time: " + timeStr).withStyle(s -> s.withBold(true)), centerX, yPos, 0xFFAA00);

            // --- СКОРОСТЬ ---
            int multiplier = Math.round(currentRate / 20.0f);
            
            if (multiplier <= 1 && isSleeping) {
                guiGraphics.drawCenteredString(mc.font, Component.literal("Accelerating..."), centerX, yPos + 15, 0xAAAAAA);
            } 
            else if (multiplier > 1) {
                int color = (multiplier > 15) ? 0xFF5555 : 0x55FF55;
                String speedStr = String.format(">>> %dx Speed <<<", multiplier);
                guiGraphics.drawCenteredString(mc.font, Component.literal(speedStr).withStyle(s -> s.withBold(true)), centerX, yPos + 15, color);
            }
        }
    }
}
