package ru.tckban;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Одна сцена «ТЦК забирает забаненного».
 *
 * Бусик собран из block_display (видно ванильному клиенту, клиентский мод не нужен),
 * которые сидят стопкой друг на друге поверх невидимого armor_stand — так он едет плавно.
 * «Люди» — armor_stand в зелёной кожаной броне.
 */
public final class TckSequence {

    // ---------------- НАСТРОЙКИ ----------------
    private static final double VAN_DISTANCE_START = 18.0;  // откуда приезжает бусик
    private static final double VAN_LATERAL = 3.5;          // насколько сбоку от игрока он тормозит
    private static final double GUARD_SPEED = 0.28;         // блоков/тик
    private static final double CARRY_SPEED = 0.25;
    private static final double DRIVE_AWAY_BLOCKS = 5.0;    // сколько проедут до исчезновения
    // --------------------------------------------

    private enum Phase { APPROACH, WALK_IN, CARRY, TRUNK, DEPART }

    /** блок, translation xyz, scale xyz — в координатах бусика (+z = вперёд, центр по x/z в 0). */
    private static final String[][] PARTS = {
            {"light_gray_concrete", "-1", "0.4", "-2", "2", "1.2", "4"},        // нижний корпус
            {"light_gray_concrete", "-1", "1.6", "-2", "2", "0.8", "3.2"},      // кабина/крыша
            {"black_stained_glass", "-0.9", "1.65", "1.2", "1.8", "0.7", "0.04"}, // лобовое
            {"black_stained_glass", "-1.02", "1.7", "-1.4", "0.04", "0.6", "2.2"}, // окно слева
            {"black_stained_glass", "0.98", "1.7", "-1.4", "0.04", "0.6", "2.2"},  // окно справа
            {"black_concrete", "-1.15", "0", "0.65", "0.3", "0.7", "0.7"},      // колёса
            {"black_concrete", "0.85", "0", "0.65", "0.3", "0.7", "0.7"},
            {"black_concrete", "-1.15", "0", "-1.35", "0.3", "0.7", "0.7"},
            {"black_concrete", "0.85", "0", "-1.35", "0.3", "0.7", "0.7"},
            {"glowstone", "-0.85", "0.9", "2.0", "0.3", "0.25", "0.05"},        // фары
            {"glowstone", "0.55", "0.9", "2.0", "0.3", "0.25", "0.05"},
            {"red_concrete", "-0.85", "0.9", "-2.05", "0.3", "0.25", "0.05"},   // стопы
            {"red_concrete", "0.55", "0.9", "-2.05", "0.3", "0.25", "0.05"},
            {"green_concrete", "-1.01", "0.95", "-1.8", "0.03", "0.25", "3.6"}, // полоса слева
            {"green_concrete", "0.98", "0.95", "-1.8", "0.03", "0.25", "3.6"},  // полоса справа
            {"gray_concrete", "-0.8", "0.6", "-2.03", "1.6", "1.2", "0.03"},    // дверь багажника
            {"black_concrete", "-1", "0.4", "2.0", "2", "0.2", "0.08"},         // бампер
    };

    private static final String GUARD_NBT =
            "{id:\"minecraft:armor_stand\",ShowArms:1b,NoBasePlate:1b,NoGravity:1b,Silent:1b,"
                    + "Tags:[\"tckban\"],Rotation:[%.1ff,0f],"
                    + "CustomName:'{\"text\":\"ТЦК\",\"color\":\"green\",\"bold\":true}',CustomNameVisible:1b,"
                    + "Pose:{RightArm:[-80f,-15f,0f],LeftArm:[-80f,15f,0f]},"
                    + "ArmorItems:["
                    + "{id:\"minecraft:leather_boots\",count:1,components:{\"minecraft:dyed_color\":3881787}},"
                    + "{id:\"minecraft:leather_leggings\",count:1,components:{\"minecraft:dyed_color\":5597999}},"
                    + "{id:\"minecraft:leather_chestplate\",count:1,components:{\"minecraft:dyed_color\":5597999}},"
                    + "{id:\"minecraft:leather_helmet\",count:1,components:{\"minecraft:dyed_color\":5597999}}]}";

    private final ServerWorld world;
    private final ServerPlayerEntity player;
    private final Text reason;

    private final Vec3d dir;      // куда смотрит и едет бусик
    private final Vec3d side;     // перпендикуляр
    private final double groundY;
    private final Vec3d victimPos;
    private final Vec3d vanStop;
    private final float vanYaw;

    private Vec3d vanPos;
    private Entity van;
    private final Entity[] guards = new Entity[2];
    private final Vec3d[] guardTargets = new Vec3d[2];

    private Phase phase = Phase.APPROACH;
    private int timer = 0;
    private double speed = 0;
    private Vec3d carryPos;
    private Vec3d carryTarget;
    private Vec3d carryDir;
    private Vec3d departFrom;
    private boolean finished = false;

    public TckSequence(ServerPlayerEntity player, Text reason) {
        this.player = player;
        this.reason = reason;
        this.world = player.getServerWorld();

        player.stopRiding();

        // Бусик едет навстречу взгляду игрока.
        Direction facing = player.getHorizontalFacing().getOpposite();
        this.dir = new Vec3d(facing.getOffsetX(), 0, facing.getOffsetZ());
        this.side = new Vec3d(-dir.z, 0, dir.x);

        BlockPos.Mutable m = player.getBlockPos().mutableCopy();
        for (int i = 0; i < 24 && world.getBlockState(m.down()).isAir(); i++) {
            m.move(Direction.DOWN);
        }
        this.groundY = m.getY();

        this.victimPos = new Vec3d(player.getX(), groundY, player.getZ());
        this.vanStop = victimPos.add(side.multiply(VAN_LATERAL));
        this.vanPos = vanStop.subtract(dir.multiply(VAN_DISTANCE_START));
        this.vanYaw = yawOf(dir);

        // Игрок не может двигаться и прыгать.
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 20 * 60, 10, false, false, false));
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.JUMP_BOOST, 20 * 60, 200, false, false, false));

        this.van = spawn(buildVanNbt(vanYaw), vanPos);
    }

    /** @return true, когда сцена закончена. */
    public boolean tick() {
        if (finished) {
            return true;
        }
        if (van == null) {            // не удалось заспавнить — просто кикаем
            finishAndKick();
            return true;
        }
        if (player.isRemoved() || van.isRemoved()) {
            abort();
            return true;
        }
        timer++;

        switch (phase) {
            case APPROACH -> tickApproach();
            case WALK_IN -> tickWalkIn();
            case CARRY -> tickCarry();
            case TRUNK -> tickTrunk();
            case DEPART -> tickDepart();
        }
        return finished;
    }

    // ------------------------------------------------------------ фазы

    private void tickApproach() {
        double remaining = vanStop.subtract(vanPos).dotProduct(dir);
        if (remaining <= 0.05) {
            vanPos = vanStop;
            moveVan();
            sound(vanStop, SoundEvents.BLOCK_IRON_DOOR_OPEN, 1.0f, 0.8f);
            spawnGuards();
            phase = Phase.WALK_IN;
            timer = 0;
            return;
        }
        double step = Math.min(Math.min(0.6, 0.06 + remaining * 0.12), remaining);
        vanPos = vanPos.add(dir.multiply(step));
        moveVan();
        if (timer % 6 == 1) {
            sound(vanPos, SoundEvents.ENTITY_MINECART_RIDING, 0.7f, 0.6f);
        }
    }

    private void tickWalkIn() {
        boolean arrived = true;
        for (int i = 0; i < 2; i++) {
            if (guards[i] == null) {
                continue;
            }
            Vec3d cur = guards[i].getPos();
            Vec3d d = guardTargets[i].subtract(cur);
            d = new Vec3d(d.x, 0, d.z);
            double len = d.length();
            float yaw = len > 0.001 ? yawOf(d) : yawOf(victimPos.subtract(cur));
            if (len > 0.15) {
                arrived = false;
                cur = cur.add(d.multiply(Math.min(GUARD_SPEED, len) / len));
            }
            guards[i].refreshPositionAndAngles(cur.x, groundY, cur.z, yaw, 0f);
        }
        teleportPlayer(victimPos);
        if (arrived || timer > 80) {
            sound(victimPos, SoundEvents.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 0.7f);
            carryPos = victimPos;
            carryTarget = vanStop.subtract(dir.multiply(2.9));
            carryDir = carryTarget.subtract(carryPos).normalize();
            phase = Phase.CARRY;
            timer = 0;
        }
    }

    private void tickCarry() {
        double len = carryTarget.subtract(carryPos).length();
        if (len <= 0.3) {
            sound(carryTarget, SoundEvents.BLOCK_IRON_TRAPDOOR_OPEN, 1.0f, 0.7f);
            phase = Phase.TRUNK;
            timer = 0;
            return;
        }
        carryPos = carryPos.add(carryDir.multiply(CARRY_SPEED));
        Vec3d perp = new Vec3d(-carryDir.z, 0, carryDir.x);
        float yaw = yawOf(carryDir);
        placeGuard(0, carryPos.add(perp.multiply(0.8)), yaw);
        placeGuard(1, carryPos.subtract(perp.multiply(0.8)), yaw);
        teleportPlayer(carryPos);
    }

    private void tickTrunk() {
        Vec3d trunk = vanStop.subtract(dir.multiply(1.0));
        if (timer < 10) {
            teleportPlayer(carryTarget);
        } else {
            if (timer == 10) {
                player.addStatusEffect(new StatusEffectInstance(StatusEffects.INVISIBILITY, 20 * 60, 0, false, false, false));
                player.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 20 * 60, 0, false, false, false));
                sound(vanStop, SoundEvents.BLOCK_IRON_TRAPDOOR_CLOSE, 1.0f, 0.6f);
            }
            teleportPlayer(trunk);
        }
        if (timer == 16) {
            for (int i = 0; i < 2; i++) {
                if (guards[i] != null) {
                    guards[i].discard();
                    guards[i] = null;
                }
            }
            sound(vanStop, SoundEvents.BLOCK_IRON_DOOR_CLOSE, 1.0f, 0.8f);
        }
        if (timer >= 28) {
            phase = Phase.DEPART;
            timer = 0;
            speed = 0.1;
            departFrom = vanPos;
        }
    }

    private void tickDepart() {
        speed = Math.min(0.6, speed + 0.02);
        vanPos = vanPos.add(dir.multiply(speed));
        moveVan();
        teleportPlayer(vanPos.subtract(dir.multiply(1.0)));
        if (timer % 4 == 0) {
            sound(vanPos, SoundEvents.ENTITY_MINECART_RIDING, 0.8f, 0.7f);
        }
        if (vanPos.subtract(departFrom).length() >= DRIVE_AWAY_BLOCKS) {
            world.spawnParticles(ParticleTypes.POOF, vanPos.x, vanPos.y + 1.2, vanPos.z, 40, 1.0, 0.8, 1.0, 0.05);
            world.spawnParticles(ParticleTypes.CLOUD, vanPos.x, vanPos.y + 1.2, vanPos.z, 20, 0.8, 0.6, 0.8, 0.02);
            sound(vanPos, SoundEvents.ENTITY_ENDERMAN_TELEPORT, 1.0f, 0.6f);
            finishAndKick();
        }
    }

    // ------------------------------------------------------------ завершение

    private void finishAndKick() {
        finished = true;
        discardAll();
        if (!player.isRemoved()) {
            player.clearStatusEffects();
            TckBanMod.release(player, reason);
        }
    }

    /** Аварийная остановка (игрок вышел / сервер выключается): убираем всё и кикаем, если игрок ещё тут. */
    public void abort() {
        if (finished) {
            return;
        }
        finished = true;
        discardAll();
        if (!player.isRemoved()) {
            player.clearStatusEffects();
            TckBanMod.release(player, reason);
        }
    }

    private void discardAll() {
        List<Entity> all = new ArrayList<>();
        if (van != null) {
            collect(van, all);
        }
        for (Entity g : guards) {
            if (g != null) {
                all.add(g);
            }
        }
        for (Entity e : all) {
            e.discard();
        }
    }

    private static void collect(Entity e, List<Entity> out) {
        out.add(e);
        for (Entity p : e.getPassengerList()) {
            collect(p, out);
        }
    }

    // ------------------------------------------------------------ утилиты

    private void spawnGuards() {
        Vec3d base = vanStop.subtract(side.multiply(1.3));
        Vec3d[] starts = {base.add(dir.multiply(0.5)), base.subtract(dir.multiply(0.5))};
        guardTargets[0] = victimPos.add(dir.multiply(0.8));
        guardTargets[1] = victimPos.subtract(dir.multiply(0.8));
        for (int i = 0; i < 2; i++) {
            float yaw = yawOf(guardTargets[i].subtract(starts[i]));
            guards[i] = spawn(String.format(Locale.ROOT, GUARD_NBT, yaw), new Vec3d(starts[i].x, groundY, starts[i].z));
        }
    }

    private void placeGuard(int i, Vec3d pos, float yaw) {
        if (guards[i] != null) {
            guards[i].refreshPositionAndAngles(pos.x, groundY, pos.z, yaw, 0f);
        }
    }

    private void moveVan() {
        van.refreshPositionAndAngles(vanPos.x, vanPos.y, vanPos.z, vanYaw, 0f);
    }

    private void teleportPlayer(Vec3d p) {
        player.networkHandler.requestTeleport(p.x, p.y, p.z, player.getYaw(), player.getPitch());
    }

    private void sound(Vec3d p, SoundEvent s, float vol, float pitch) {
        world.playSound(null, p.x, p.y, p.z, s, SoundCategory.NEUTRAL, vol, pitch);
    }

    private static float yawOf(Vec3d d) {
        return (float) (MathHelper.atan2(-d.x, d.z) * 57.29577951308232);
    }

    private Entity spawn(String snbt, Vec3d pos) {
        try {
            NbtCompound nbt = StringNbtReader.parse(snbt);
            Entity e = EntityType.loadEntityWithPassengers(nbt, world, SpawnReason.COMMAND, entity -> {
                entity.refreshPositionAndAngles(pos.x, pos.y, pos.z, entity.getYaw(), entity.getPitch());
                return entity;
            });
            if (e == null) {
                return null;
            }
            world.spawnNewEntityAndPassengers(e);
            return e;
        } catch (CommandSyntaxException ex) {
            ex.printStackTrace();
            return null;
        }
    }

    private static String buildVanNbt(float yaw) {
        String chain = "";
        for (int i = PARTS.length - 1; i >= 0; i--) {
            String[] p = PARTS[i];
            chain = String.format(Locale.ROOT,
                    "{id:\"minecraft:block_display\",Tags:[\"tckban\"],Rotation:[%.1ff,0f],"
                            + "block_state:{Name:\"minecraft:%s\"},"
                            + "transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],"
                            + "scale:[%sf,%sf,%sf],translation:[%sf,%sf,%sf]}%s}",
                    yaw, p[0], p[4], p[5], p[6], p[1], p[2], p[3],
                    chain.isEmpty() ? "" : ",Passengers:[" + chain + "]");
        }
        return String.format(Locale.ROOT,
                "{id:\"minecraft:armor_stand\",Invisible:1b,Marker:1b,NoGravity:1b,Silent:1b,"
                        + "Tags:[\"tckban\"],Rotation:[%.1ff,0f],Passengers:[%s]}",
                yaw, chain);
    }
}
