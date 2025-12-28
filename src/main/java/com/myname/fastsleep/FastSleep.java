package com.myname.fastsleep;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.List;

@Mod("fastsleep")
public class FastSleep {

    // === КОНФИГ ИЗ SLEEPWARP ===
    private static final int MAX_TICKS_ADDED = 100; 
    private static final double PLAYER_MULTIPLIER = 1.0; 
    private static final boolean ACTION_BAR_MESSAGES = true; 
    private static final boolean USE_SLEEP_PERCENTAGE = true;

    private static final int DAY_LENGTH_TICKS = 24000;
    
    // Состояние
    private boolean isWarping = false;
    private int originalSleepRule = 100;

    public FastSleep() {
        NeoForge.EVENT_BUS.register(this);
    }

    // Сброс при старте
    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        isWarping = false;
        // Мы убрали проверку hasRule, так как стандартные правила есть всегда
    }

    /**
     * Основной цикл. Порт логики SleepWarp.
     */
    @SubscribeEvent
    public void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel().isClientSide() || event.getLevel().dimension() != Level.OVERWORLD) return;

        ServerLevel world = (ServerLevel) event.getLevel();
        MinecraftServer server = world.getServer();

        // ИСПРАВЛЕНИЕ 1: RULE_DAYLIGHT вместо RULE_DO_DAYLIGHT_CYCLE
        if (!world.getServer().isSingleplayer() && !world.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)) return;
        
        List<ServerPlayer> players = world.players();
        int totalPlayers = players.size();
        if (totalPlayers == 0) return;

        long sleepingPlayers = players.stream().filter(ServerPlayer::isSleepingLongEnough).count();

        if (sleepingPlayers == 0) {
            if (isWarping) {
                stopWarp(world);
            }
            return;
        }

        if (USE_SLEEP_PERCENTAGE) {
            int percentRequired = world.getGameRules().getInt(GameRules.RULE_PLAYERS_SLEEPING_PERCENTAGE);
            int minimumSleeping = Math.max(1, Mth.ceil((totalPlayers * percentRequired) / 100.0F));
            if (sleepingPlayers < minimumSleeping) return;
        }

        // == НАЧАЛО ВАРПА ==
        if (!isWarping) {
            startWarp(world);
        }

        // 2. Математика расчета скорости
        long worldTime = world.getDayTime() % DAY_LENGTH_TICKS;
        int warpTickCount;

        if (worldTime + MAX_TICKS_ADDED < DAY_LENGTH_TICKS) {
            if (totalPlayers == 1) {
                warpTickCount = MAX_TICKS_ADDED;
            } else {
                double sleepingRatio = (double) sleepingPlayers / totalPlayers;
                double scaledRatio = sleepingRatio * PLAYER_MULTIPLIER;
                double tickMultiplier = scaledRatio / ((scaledRatio * 2) - PLAYER_MULTIPLIER - sleepingRatio + 1);
                warpTickCount = Math.toIntExact(Math.round(MAX_TICKS_ADDED * tickMultiplier));
            }
        } else {
            warpTickCount = Math.toIntExact(DAY_LENGTH_TICKS % worldTime);
        }

        // 3. ИСПОЛНЕНИЕ (Крутим время)
        long currentTime = world.getDayTime();
        world.setDayTime(currentTime + warpTickCount);

        // ИСПРАВЛЕНИЕ 2: RULE_DAYLIGHT
        boolean doDaylightCycle = world.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT);
        ClientboundSetTimePacket packet = new ClientboundSetTimePacket(world.getGameTime(), world.getDayTime(), doDaylightCycle);
        server.getPlayerList().broadcastAll(packet, world.dimension());

        // 4. Визуал (Action Bar)
        if (ACTION_BAR_MESSAGES) {
            sendActionBar(world, sleepingPlayers, totalPlayers, warpTickCount);
        }
        
        // Если наступило утро
        long newTime = world.getDayTime() % DAY_LENGTH_TICKS;
        if (newTime < 1000 && worldTime > 20000) {
             stopWarp(world);
             for(ServerPlayer p : players) {
                 if(p.isSleeping()) p.stopSleeping();
             }
        }
    }

    private void startWarp(ServerLevel world) {
        isWarping = true;
        GameRules rules = world.getGameRules();
        originalSleepRule = rules.getInt(GameRules.RULE_PLAYERS_SLEEPING_PERCENTAGE);
        rules.getRule(GameRules.RULE_PLAYERS_SLEEPING_PERCENTAGE).set(101, world.getServer());
    }

    private void stopWarp(ServerLevel world) {
        isWarping = false;
        world.getGameRules().getRule(GameRules.RULE_PLAYERS_SLEEPING_PERCENTAGE).set(originalSleepRule, world.getServer());
    }

    private void sendActionBar(ServerLevel world, long sleepingPlayers, int totalPlayers, int warpTickCount) {
        long worldTime = world.getDayTime() % DAY_LENGTH_TICKS;
        long remainingTicks = DAY_LENGTH_TICKS - worldTime;
        
        if (remainingTicks < 0) remainingTicks = 0;

        Component text;

        if (totalPlayers > 1) {
            ChatFormatting color = ChatFormatting.DARK_GREEN;
            text = Component.literal("⌛ ")
                    .withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(sleepingPlayers + "/" + totalPlayers + " sleeping. ")
                    .withStyle(color));
        } else {
            text = Component.literal("⌛ ").withStyle(ChatFormatting.GOLD);
        }

        // Расчет секунд до утра
        long ticksPerSecond = 20 * (Math.max(1, warpTickCount / 10)); 
        if (ticksPerSecond == 0) ticksPerSecond = 20; // Защита от деления на 0
        
        long realSecondsLeft = remainingTicks / ticksPerSecond; 
        if (realSecondsLeft < 0) realSecondsLeft = 0;

        text = text.copy().append(Component.literal("Time until dawn: " + realSecondsLeft + "s")
                   .withStyle(ChatFormatting.YELLOW));

        for (ServerPlayer player : world.players()) {
            player.sendSystemMessage(text, true); 
        }
    }
}
