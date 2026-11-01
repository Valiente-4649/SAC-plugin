package sac.plugin.info.SACplugin.skill;

import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * ソードスキルの「システムアシスト移動」だけを担当する。
 * ダメージや無敵処理には触れないため、モーション中でもMobの攻撃判定は通常通り有効。
 */
public final class SwordSkillMotionController {
    private SwordSkillMotionController() {}
    private static final int HIT_INTERVAL = 5; // SAOSkillListener: 5 ticks between hits
    private static final Map<UUID, BukkitRunnable> ACTIVE = new HashMap<>();
    private static final Map<UUID, Runnable> CLEANUP = new HashMap<>();

    public static int start(Plugin plugin, Player player, SwordSkillData skill) {
        SwordSkillData.SkillMotion root = skill.motion;
        if (root == null || root.type == null || root.type.equalsIgnoreCase("NONE")) return 0;
        List<SwordSkillData.SkillMotion> phases = new ArrayList<>();
        if (root.type.equalsIgnoreCase("COMBO")) {
            if (root.steps == null || root.steps.isEmpty()) return 0;
            phases.addAll(root.steps);
        } else phases.add(root);
        stop(player);
        Vector forward = player.getLocation().getDirection().setY(0);
        if (forward.lengthSquared() < 0.00001) forward = new Vector(0, 0, 1);
        forward.normalize();
        final Vector fwd = forward;
        final Vector right = new Vector(-fwd.getZ(), 0, fwd.getX()).normalize();
        final int maxTicks = root.type.equalsIgnoreCase("COMBO") ? Math.max(skill.hits, phases.size()) * HIT_INTERVAL : 30;
        final float originalSpeed = player.getWalkSpeed();
        final PermissionAttachment exemption = player.addAttachment(plugin, "grim.exempt", true);
        player.setWalkSpeed(0f);
        BukkitRunnable runner = new BukkitRunnable() {
            int tick = 0, lastPhase = -1, phaseTick = 0;
            double traveled = 0, spinYaw = 0;
            Location previous = player.getLocation().clone();
            boolean blocked = false;
            final float initialYaw = player.getLocation().getYaw();
            @Override public void run() {
                if (!player.isOnline() || player.isDead() || tick >= maxTicks) { finish(); return; }
                int index = root.type.equalsIgnoreCase("COMBO") ? Math.min(phases.size()-1, tick / HIT_INTERVAL) : 0;
                if (index != lastPhase) {
                    lastPhase = index; phaseTick = 0; traveled = 0; blocked = false;
                    previous = player.getLocation().clone();
                }
                SwordSkillData.SkillMotion phase = phases.get(index);
                String type = phase == null || phase.type == null ? "NONE" : phase.type.toUpperCase(Locale.ROOT);
                int duration = type.equals("SPIN") ? 5 : type.equals("DASH") ? 25 : 2;
                if (phaseTick < duration && !blocked) {
                    if (type.equals("SPIN")) {
                        // 5 camera steps of 72 degrees. No displacement.
                        Location loc = player.getLocation();
                        float sign = "LEFT".equalsIgnoreCase(phase.direction) ? -1f : 1f;
                        loc.setYaw(loc.getYaw() + 72f * sign);
                        player.teleport(loc);
                        spinYaw += 72 * sign;
                    } else if (type.equals("STEP") || type.equals("SIDESTEP") || type.equals("DASH")) {
                        Vector direction = type.equals("SIDESTEP") ? right.clone() : fwd.clone();
                        if (type.equals("SIDESTEP") && "LEFT".equalsIgnoreCase(phase.direction)) direction.multiply(-1);
                        if (type.equals("STEP") && "BACKWARD".equalsIgnoreCase(phase.direction)) direction.multiply(-1);
                        double goal = type.equals("DASH") ? Math.max(0, Math.min(32, phase.distance)) : 0.5;
                        if (type.equals("DASH") && goal <= 0) blocked = true;
                        else {
                            if (previous.getWorld().equals(player.getWorld())) traveled += horizontalDistance(previous, player.getLocation());
                            previous = player.getLocation().clone();
                            double remaining = goal - traveled;
                            if (remaining <= 0.05) blocked = true;
                            else {
                                double speed = Math.min(remaining, type.equals("DASH") ? 0.65 : 0.26);
                                double safe = safeDistance(player, direction, speed);
                                if (safe < 0.045) blocked = true;
                                else {
                                    Vector velocity = direction.multiply(safe);
                                    velocity.setY(player.isOnGround() ? 0.04 : player.getVelocity().getY());
                                    player.setVelocity(velocity);
                                }
                            }
                        }
                    }
                }
                phaseTick++; tick++;
                if (!root.type.equalsIgnoreCase("COMBO") && (blocked || phaseTick >= duration)) finish();
            }
            private void finish() {
                cancel();
                if (ACTIVE.get(player.getUniqueId()) == this) { ACTIVE.remove(player.getUniqueId()); CLEANUP.remove(player.getUniqueId()); }
                if (player.isOnline()) {
                    player.setWalkSpeed(originalSpeed);
                    Vector v = player.getVelocity();
                    player.setVelocity(new Vector(0, Math.min(v.getY(), 0.1), 0));
                }
                // Grace period allows the last movement packet to settle.
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    try { player.removeAttachment(exemption); } catch (IllegalArgumentException ignored) {}
                }, 3L);
            }
        };
        ACTIVE.put(player.getUniqueId(), runner);
        CLEANUP.put(player.getUniqueId(), () -> {
            if (player.isOnline()) player.setWalkSpeed(originalSpeed);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                try { player.removeAttachment(exemption); } catch (IllegalArgumentException ignored) {}
            }, 3L);
        });
        runner.runTaskTimer(plugin, 0L, 1L);
        return Math.max(0, root.attackDelay);
    }

    public static void stop(Player player) {
        BukkitRunnable running = ACTIVE.remove(player.getUniqueId());
        if (running != null) { running.cancel(); Runnable cleanup = CLEANUP.remove(player.getUniqueId()); if (cleanup != null) cleanup.run(); }
    }

    private static double horizontalDistance(Location a, Location b) {
        double x = a.getX() - b.getX(), z = a.getZ() - b.getZ();
        return Math.sqrt(x*x + z*z);
    }

    private static double safeDistance(Player player, Vector direction, double wanted) {
        Location origin = player.getLocation();
        // Check at foot and torso height; blocks must not be passed through.
        for (double y : new double[]{0.15, 0.9, 1.65}) {
            RayTraceResult hit = player.getWorld().rayTraceBlocks(origin.clone().add(0, y, 0), direction, wanted + 0.35);
            if (hit != null && hit.getHitPosition() != null) {
                double d = hit.getHitPosition().distance(origin.clone().add(0,y,0).toVector()) - 0.35;
                wanted = Math.min(wanted, Math.max(0, d));
            }
        }
        BoundingBox playerBox = player.getBoundingBox();
        for (Entity e : player.getNearbyEntities(3, 3, 3)) {
            if (!(e instanceof LivingEntity) || e instanceof Player || e instanceof ArmorStand || e.isDead()) continue;
            if (e instanceof Villager v) {
                if (v.getCustomName() != null) continue;
                boolean npc = false;
                for (String tag : v.getScoreboardTags()) if (tag.startsWith("sao_shop_") || tag.startsWith("sao_quest_")) npc = true;
                if (npc) continue;
            }
            // Sweep the player's box in small increments, stop before intersecting mob box.
            BoundingBox mob = e.getBoundingBox();
            for (double d = 0.05; d <= wanted + 0.001; d += 0.05) {
                BoundingBox moved = playerBox.clone().shift(direction.clone().multiply(d));
                if (moved.overlaps(mob)) { wanted = Math.max(0, d - 0.05); break; }
            }
        }
        return Math.max(0, wanted);
    }
}