package sac.plugin.info.SACplugin.skill;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Visual/audio feedback only: never changes damage, cooldowns, AI or movement. */
public final class SACCombatFeedback {
    private SACCombatFeedback() {}
    private static final Map<UUID, HitChain> CHAINS = new HashMap<>();
    private static final long CHAIN_RESET_MS = 1300L;

    private static final class HitChain {
        long lastHit;
        int count;
    }

    public static void normalHit(Player player, LivingEntity target, String weaponCategory) {
        if (!target.isValid()) return;
        long now = System.currentTimeMillis();
        HitChain chain = CHAINS.computeIfAbsent(player.getUniqueId(), k -> new HitChain());
        chain.count = now - chain.lastHit <= CHAIN_RESET_MS ? (chain.count % 3) + 1 : 1;
        chain.lastHit = now;
        int stage = chain.count;

        Location center = target.getLocation().clone().add(0, Math.min(1.35, target.getHeight() * 0.62), 0);
        Vector forward = horizontal(player.getLocation().getDirection());
        Vector right = new Vector(-forward.getZ(), 0, forward.getX());
        boolean thrust = "細剣".equals(weaponCategory) || "槍".equals(weaponCategory);
        boolean unarmed = "体術".equals(weaponCategory);
        if (thrust) {
            target.getWorld().spawnParticle(Particle.CRIT, center, 9, 0.16, 0.16, 0.16, 0.05);
            target.getWorld().spawnParticle(Particle.END_ROD, center, 3, 0.1, 0.1, 0.1, 0.015);
        } else {
            Vector from = right.clone().multiply(stage == 2 ? -0.75 : 0.75).add(new Vector(0, stage == 3 ? 0.65 : -0.3, 0));
            Vector to = from.clone().multiply(-1);
            slashLine(center, from, to, stage == 3 ? Color.fromRGB(255, 225, 155) : Color.fromRGB(235, 245, 255), stage == 3 ? 13 : 9);
            target.getWorld().spawnParticle(Particle.CRIT, center, stage == 3 ? 12 : 5, 0.18, 0.2, 0.18, 0.08);
        }
        if (stage == 3) {
            target.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, center, 9, 0.22, 0.25, 0.22, 0.04);
            player.playSound(center, unarmed ? Sound.ENTITY_PLAYER_ATTACK_STRONG : Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.65f, 0.88f);
        } else {
            player.playSound(center, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.4f, stage == 1 ? 1.4f : 1.65f);
        }
        // Keep the map bounded during long-running servers.
        if (CHAINS.size() > 512) CHAINS.entrySet().removeIf(e -> now - e.getValue().lastHit > 10000L);
    }

    public static void parrySuccess(Player player, LivingEntity attacker) {
        if (attacker == null || !attacker.isValid()) return;
        Location at = player.getEyeLocation().clone();
        Vector towards = attacker.getLocation().toVector().subtract(at.toVector());
        if (towards.lengthSquared() > 0.0001) at.add(towards.normalize().multiply(0.85));
        at.add(0, -0.25, 0);
        player.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, at, 23, 0.26, 0.27, 0.26, 0.12);
        player.getWorld().spawnParticle(Particle.CRIT, at, 17, 0.28, 0.28, 0.28, 0.12);
        player.getWorld().spawnParticle(Particle.FLASH, at, 1, 0, 0, 0, 0);
        ring(at, Color.fromRGB(255, 235, 160), 0.75, 16);
        player.playSound(at, Sound.BLOCK_ANVIL_LAND, 0.55f, 1.9f);
        player.playSound(at, Sound.ITEM_SHIELD_BLOCK, 0.85f, 1.25f);
    }

    /** Sword skill hit: audio only. No extra white sparks, rods, rings or flashes.
     * Existing colored sword-slash effects in SwordSkillExecutor remain unchanged.
     */
    public static void skillHit(Player player, LivingEntity target, boolean finalHit, boolean counter) {
        if (target == null || !target.isValid()) return;
        Location center = target.getLocation().clone().add(0, Math.min(1.4, target.getHeight() * 0.6), 0);
        if (finalHit) {
            player.playSound(center, Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.9f, 0.75f);
        } else {
            player.playSound(center, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.5f, 1.5f);
        }
    }

    public static void clear(Player player) { CHAINS.remove(player.getUniqueId()); }

    private static Vector horizontal(Vector direction) {
        Vector v = direction.clone().setY(0);
        return v.lengthSquared() < 0.0001 ? new Vector(0, 0, 1) : v.normalize();
    }

    private static void slashLine(Location center, Vector from, Vector to, Color color, int points) {
        Particle.DustOptions dust = new Particle.DustOptions(color, 0.85f);
        for (int i = 0; i <= points; i++) {
            double t = i / (double) points;
            Vector offset = from.clone().multiply(1 - t).add(to.clone().multiply(t));
            center.getWorld().spawnParticle(Particle.DUST, center.clone().add(offset), 1, 0, 0, 0, 0, dust);
        }
    }

    private static void ring(Location center, Color color, double radius, int points) {
        Particle.DustOptions dust = new Particle.DustOptions(color, 1.0f);
        for (int i = 0; i < points; i++) {
            double angle = 2 * Math.PI * i / points;
            Location point = center.clone().add(Math.cos(angle) * radius, Math.sin(angle) * radius, 0);
            center.getWorld().spawnParticle(Particle.DUST, point, 1, 0, 0, 0, 0, dust);
        }
    }
}
