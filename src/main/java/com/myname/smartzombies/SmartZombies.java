package com.myname.smartzombies;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Mod("smartzombies")
public class SmartZombies {

    // --- ПАМЯТЬ МОДА ---
    private static final Map<Integer, Integer> actionCooldown = new HashMap<>();
    private static final Map<Integer, Float> breakProgress = new HashMap<>();
    private static final Map<Integer, BlockPos> breakTarget = new HashMap<>();
    
    private static final Map<Integer, UUID> memoryTarget = new HashMap<>();
    private static final Map<Integer, Integer> memoryTimer = new HashMap<>();

    private static final Map<Integer, Vec3> lastPos = new HashMap<>();
    private static final Map<Integer, Integer> stuckTimer = new HashMap<>();

    public SmartZombies(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.register(this);
    }

    // ================== ИНИЦИАЛИЗАЦИЯ ==================

    @SubscribeEvent
    public void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        
        if (event.getEntity() instanceof Zombie zombie) {
            if (zombie.isBaby()) zombie.setBaby(false); 

            if (zombie.getAttribute(Attributes.STEP_HEIGHT) != null) 
                zombie.getAttribute(Attributes.STEP_HEIGHT).setBaseValue(1.5); 
            if (zombie.getAttribute(Attributes.FOLLOW_RANGE) != null) 
                zombie.getAttribute(Attributes.FOLLOW_RANGE).setBaseValue(64.0);

            if (zombie.getItemBySlot(EquipmentSlot.HEAD).isEmpty()) {
                RandomSource rand = zombie.getRandom();
                if (rand.nextFloat() < 0.40f) { 
                    zombie.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_PICKAXE));
                    zombie.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.COBBLESTONE, 64)); 
                } else if (rand.nextFloat() < 0.30f) { 
                    zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
                    zombie.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
                    zombie.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
                }
            }
            updateZombieSpeed(zombie);
        }
    }

    // ================== ГЛАВНЫЙ ЦИКЛ (AI) ==================

    @SubscribeEvent
    public void onZombieTick(EntityTickEvent.Pre event) {
        Entity entity = event.getEntity();
        if (entity.level().isClientSide() || !(entity instanceof Zombie)) return;

        Zombie zombie = (Zombie) entity;
        ServerLevel level = (ServerLevel) zombie.level();
        int zId = zombie.getId();
        
        // --- Checks ---
        if (zombie.isBaby()) zombie.setBaby(false);
        if (zombie.tickCount % 20 == 0) updateZombieSpeed(zombie);

        LivingEntity target = getSmartTarget(zombie, level, zId);
        if (target == null) {
            resetBreaking(level, zId);
            return;
        }

        int cooldown = actionCooldown.getOrDefault(zId, 0);
        if (cooldown > 0) {
            actionCooldown.put(zId, cooldown - 1);
            return; 
        }

        double distSqr = zombie.distanceToSqr(target);
        double diffY = target.getY() - zombie.getY();
        double flatDistSqr = (target.getX()-zombie.getX())*(target.getX()-zombie.getX()) + (target.getZ()-zombie.getZ())*(target.getZ()-zombie.getZ());
        
        checkStuck(zombie, zId);
        int stuckTicks = stuckTimer.getOrDefault(zId, 0);
        boolean isStuck = stuckTicks > 15;

        handleCombat(zombie, target, distSqr);
        
        // Swim up
        if (zombie.isInWater() && diffY > 0.5) {
            zombie.getJumpControl().jump();
        }

        // Basic Move
        if (!zombie.getNavigation().isInProgress() && flatDistSqr > 2.0) {
            zombie.getNavigation().moveTo(target, 1.2);
        }

        boolean active = false;

        // ========================== МОЗГ ==========================

        // --- СЦЕНАРИЙ 1: "КРЫСА" (DIGGING DOWN) ---
        // Цель снизу, мы прямо над ней
        if (diffY < -2.5 && flatDistSqr < 2.5 && zombie.onGround()) {
             boolean hasPath = false;
             
             // ПРОВЕРКА ПУТИ: Есть ли нормальный проход?
             // Если мы НЕ застряли жестко, пробуем найти путь
             if (stuckTicks < 30) {
                 // FIX: getCurrentPath() -> getPath() for 1.21.1 mappings
                 if (zombie.getNavigation().isInProgress() && zombie.getNavigation().getPath() != null) {
                     // Мы уже идем по пути, значит, надежда есть
                     hasPath = true; 
                 } 
                 else if (zombie.tickCount % 10 == 0) { // Проверяем переодически
                     Path path = zombie.getNavigation().createPath(target, 1);
                     if (path != null && path.canReach()) {
                         zombie.getNavigation().moveTo(path, 1.2);
                         hasPath = true;
                     }
                 }
             }

             // Если пути НЕТ или мы ЗАСТРЯЛИ даже на пути -> ЛОМАЕМ ПОЛ
             if (!hasPath) {
                 BlockPos floorPos = zombie.blockPosition().below();
                 if (!level.getBlockState(floorPos).isAir()) {
                     zombie.getNavigation().stop(); 
                     zombie.setDeltaMovement(0, zombie.getDeltaMovement().y, 0);
                     
                     processBreaking(zombie, level, floorPos);
                     active = true;
                 }
             }
        }

        // --- СЦЕНАРИЙ 2: "АЛЬПИНИСТ" (PILLARING) ---
        // Цель высоко и близко
        if (!active && diffY > 2.5 && flatDistSqr < 9.0) {
            BlockPos headPos = zombie.blockPosition().above();
            BlockPos ceilingPos = headPos.above();
            
            // Мешает потолок? -> ЛОМАЕМ
            if (!level.getBlockState(ceilingPos).isAir()) {
                processBreaking(zombie, level, ceilingPos);
                active = true;
            } 
            // Чисто? -> СТРОИМ
            else if (zombie.onGround()) {
                if (level.getBlockState(zombie.blockPosition().above(2)).isAir()) {
                    zombie.getNavigation().stop();
                    zombie.setPos(Math.floor(zombie.getX()) + 0.5, zombie.getY(), Math.floor(zombie.getZ()) + 0.5);
                    
                    placeBlock(level, zombie.blockPosition());
                    zombie.setPos(zombie.getX(), zombie.getY() + 1.1, zombie.getZ());
                    actionCooldown.put(zId, 8);
                    active = true;
                }
            }
        }

        // --- СЦЕНАРИЙ 3: "ТАРАН" (BREAKING WALLS) ---
        if (!active && (isStuck || zombie.horizontalCollision)) {
            if (Math.abs(diffY) < 3.0) {
                Vec3 look = target.position().subtract(zombie.position()).normalize();
                
                BlockPos targetBlock = new BlockPos(
                    (int)Math.floor(zombie.getX() + look.x),
                    (int)Math.floor(zombie.getEyeY()),
                    (int)Math.floor(zombie.getZ() + look.z)
                );
                BlockPos legsBlock = targetBlock.below();

                boolean brokeSomething = false;

                if (!level.getBlockState(targetBlock).isAir()) {
                    processBreaking(zombie, level, targetBlock);
                    brokeSomething = true;
                } 
                else if (!level.getBlockState(legsBlock).isAir()) {
                    processBreaking(zombie, level, legsBlock);
                    brokeSomething = true;
                }
                
                // ANTI-CHEESE: Ломаем полублоки/заборы если застряли
                if (!brokeSomething && stuckTicks > 40) {
                     BlockPos selfPos = zombie.blockPosition();
                     if (!level.getBlockState(selfPos).isAir()) {
                         processBreaking(zombie, level, selfPos);
                         brokeSomething = true;
                     }
                }
                
                if (brokeSomething) active = true;
            }
        }

        // --- СЦЕНАРИЙ 4: "ПАРКУРЩИК" (BRIDGING) ---
        if (!active && zombie.onGround() && flatDistSqr > 4.0) {
            if (diffY < -3.0) {
                // Gravity
            } 
            else {
                Vec3 motion = zombie.getDeltaMovement();
                BlockPos nextStep = new BlockPos(
                    (int)Math.floor(zombie.getX() + (motion.x * 2.5)), 
                    (int)Math.floor(zombie.getY() - 1.0),
                    (int)Math.floor(zombie.getZ() + (motion.z * 2.5))
                );

                if (level.getBlockState(nextStep).getCollisionShape(level, nextStep).isEmpty()) {
                    
                    // Safe Drop Check
                    boolean isSafeDrop = false;
                    for (int i = 1; i <= 3; i++) {
                        BlockPos check = nextStep.below(i);
                        if (!level.getBlockState(check).getCollisionShape(level, check).isEmpty()) {
                            isSafeDrop = true;
                            break;
                        }
                    }

                    if (!isSafeDrop) {
                        BlockPos spaceAboveBridge = nextStep.above();
                        BlockPos spaceHeadBridge = nextStep.above(2);
                        
                        if (level.getBlockState(spaceAboveBridge).isAir() && level.getBlockState(spaceHeadBridge).isAir()) {
                             BlockPos placePos = new BlockPos(
                                (int)Math.floor(zombie.getX() + motion.x),
                                (int)Math.floor(zombie.getY() - 1.0),
                                (int)Math.floor(zombie.getZ() + motion.z)
                             );
                             
                             if (level.getBlockState(placePos).isAir()) {
                                 placeBlock(level, placePos);
                                 actionCooldown.put(zId, 6);
                                 active = true;
                             }
                        }
                    }
                }
            }
        }

        if (!active) resetBreaking(level, zId);
    }

    // ================== ВСПОМОГАТЕЛЬНЫЕ ФУНКЦИИ ==================

    private void updateZombieSpeed(Zombie zombie) {
        if (zombie.getAttribute(Attributes.MOVEMENT_SPEED) == null) return;
        double baseSpeed = 0.23D;
        double penalty = 0.0D;

        int armorPieces = 0;
        if (!zombie.getItemBySlot(EquipmentSlot.FEET).isEmpty()) armorPieces++;
        if (!zombie.getItemBySlot(EquipmentSlot.LEGS).isEmpty()) armorPieces++;
        if (!zombie.getItemBySlot(EquipmentSlot.CHEST).isEmpty()) armorPieces++;
        if (!zombie.getItemBySlot(EquipmentSlot.HEAD).isEmpty()) armorPieces++;

        penalty += armorPieces * 0.015D;
        if (zombie.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.SHIELD)) penalty += 0.03D;
        if (!zombie.getItemBySlot(EquipmentSlot.MAINHAND).isEmpty()) penalty += 0.01D;

        double newSpeed = Math.max(0.10D, baseSpeed - penalty);
        double currentBase = zombie.getAttribute(Attributes.MOVEMENT_SPEED).getBaseValue();
        if (Math.abs(currentBase - newSpeed) > 0.001) {
            zombie.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(newSpeed);
        }
    }

    private void checkStuck(Zombie zombie, int zId) {
        Vec3 currentPos = zombie.position();
        Vec3 last = lastPos.get(zId);
        if (last != null && currentPos.distanceToSqr(last) < 0.0002) {
            stuckTimer.put(zId, stuckTimer.getOrDefault(zId, 0) + 1);
        } else {
            stuckTimer.put(zId, 0);
            lastPos.put(zId, currentPos);
        }
    }

    private LivingEntity getSmartTarget(Zombie zombie, ServerLevel level, int zId) {
        LivingEntity current = zombie.getTarget();
        if (current != null && current.isAlive()) {
            if (current instanceof Player p && (p.isCreative() || p.isSpectator())) {
                zombie.setTarget(null);
                memoryTarget.remove(zId);
                return null;
            }
            memoryTarget.put(zId, current.getUUID());
            memoryTimer.put(zId, 600);
            return current;
        }
        int t = memoryTimer.getOrDefault(zId, 0);
        if (t > 0) {
            memoryTimer.put(zId, t - 1);
            UUID uid = memoryTarget.get(zId);
            if (uid != null) {
                Entity e = level.getEntity(uid);
                if (e instanceof LivingEntity le && le.isAlive()) {
                    if (le instanceof Player p && (p.isCreative() || p.isSpectator())) return null;
                    zombie.setTarget(le);
                    return le;
                }
            }
        }
        return null;
    }

    private void handleCombat(Zombie zombie, LivingEntity target, double distSqr) {
        if (zombie.getHealth() < 6.0f) {
            zombie.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 20, 2));
        }
        if (distSqr > 9.0) zombie.setSprinting(true);
    }

    private void placeBlock(ServerLevel level, BlockPos pos) {
        if (!level.getEntities(null, new net.minecraft.world.phys.AABB(pos)).isEmpty()) return;

        level.setBlock(pos, Blocks.COBBLESTONE.defaultBlockState(), 3);
        level.playSound(null, pos, SoundEvents.STONE_PLACE, SoundSource.HOSTILE, 1.0f, 1.0f);
    }

    private void processBreaking(Zombie zombie, ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.getDestroySpeed(level, pos) < 0) return;

        int zId = zombie.getId();
        BlockPos oldPos = breakTarget.get(zId);

        if (oldPos == null || !oldPos.equals(pos)) {
            if (oldPos != null) level.destroyBlockProgress(zId, oldPos, -1);
            breakTarget.put(zId, pos);
            breakProgress.put(zId, 0.0f);
            return;
        }

        float progress = breakProgress.get(zId);
        float hardness = state.getDestroySpeed(level, pos);
        float multiplier = (zombie.getMainHandItem().getDestroySpeed(state) > 1.0f) ? 3.0f : 1.0f;
        
        // RAGE MODE
        int stuckTime = stuckTimer.getOrDefault(zId, 0);
        if (stuckTime > 10) multiplier *= 2.0f;
        if (stuckTime > 60) multiplier *= 4.0f;

        if (hardness > 50) multiplier = 0.0f;

        progress += (multiplier / hardness) * 2.0f; 
        breakProgress.put(zId, progress);

        int stage = (int)(progress / 10.0f);
        level.destroyBlockProgress(zId, pos, stage);
        
        if (zombie.tickCount % 5 == 0) {
            zombie.swing(InteractionHand.MAIN_HAND);
            level.playSound(null, pos, state.getSoundType().getHitSound(), SoundSource.HOSTILE, 0.5f, 1.0f);
        }

        if (progress >= 100.0f) {
            level.destroyBlock(pos, true);
            resetBreaking(level, zId);
        }
    }

    private void resetBreaking(ServerLevel level, int zId) {
        if (breakTarget.containsKey(zId)) {
            level.destroyBlockProgress(zId, breakTarget.get(zId), -1);
            breakTarget.remove(zId);
            breakProgress.remove(zId);
        }
    }
}
