package sac.plugin.info.SACplugin.skill;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import sac.plugin.info.SACplugin.SACplugin;

/**
 * ジャストパリィの演出。
 * プレイヤーとモブの間に「キラキラの円（垂直な壁状）」を出し、極小の円から GROW_TICKS(=0.15秒)かけて
 * プレイヤーより少し大きい円まで広げる。円が最大になった瞬間に onPeak（吹っ飛ばし・硬直・ダメージ反射）を実行する。
 */
public final class JustParryEffect {

    private JustParryEffect() {}

    /** 円が最大になるまでのtick数。3tick = 0.15秒 */
    public static final int GROW_TICKS = 3;
    /** 最初の円の半径 / 最大の円の半径（プレイヤーの身長1.8に対して直径2.3 = 少し大きい） */
    private static final double START_RADIUS = 0.08;
    private static final double END_RADIUS = 1.15;
    /** 円の中心の高さ（足元から） */
    private static final double RING_CENTER_HEIGHT = 1.0;
    /** プレイヤーからモブ方向へ、円の中心をどれだけ離すか（モブとの距離の半分を上限・下限で挟む） */
    private static final double WALL_DISTANCE_MAX = 1.2;
    private static final double WALL_DISTANCE_MIN = 0.6;

    private static final Particle.DustOptions GOLD = new Particle.DustOptions(Color.fromRGB(255, 225, 120), 1.0f);

    public static void play(SACplugin plugin, Player player, LivingEntity attacker, Runnable onPeak) {
        if (player == null || attacker == null) return;
        final World world = player.getWorld();

        // ---- 円の向きは発動時のプレイヤー→モブ方向（水平）で固定する。円は常にその方向を向く「壁」 ----
        Vector toMob = attacker.getLocation().toVector().subtract(player.getLocation().toVector()).setY(0);
        final double horizontal = toMob.length();
        final Vector dir;
        if (horizontal > 1.0E-4) {
            dir = toMob.multiply(1.0 / horizontal);
        } else {
            double yaw = Math.toRadians(player.getLocation().getYaw());
            dir = new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
        }
        final Vector side = new Vector(-dir.getZ(), 0, dir.getX()); // 円の横方向（壁の面内）
        final double offset = horizontal > 1.0E-4
                ? Math.min(WALL_DISTANCE_MAX, Math.max(WALL_DISTANCE_MIN, horizontal * 0.5))
                : 0.8;

        // ---- 0tick目: 極小の円 + 発動音 ----
        Location centre = centre(player, dir, offset);
        drawFrame(world, centre, side, radiusAt(0), -1.0);
        world.playSound(centre, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.2f, 1.8f);
        world.playSound(centre, Sound.ITEM_SHIELD_BLOCK, 0.9f, 1.3f);

        // ---- 1〜3tick目: 拡大。最終tick(=0.15秒後)が最大 ----
        new BukkitRunnable() {
            int k = 0;

            @Override
            public void run() {
                if (!player.isOnline()) {
                    this.cancel();
                    return;
                }
                k++;
                Location c = centre(player, dir, offset);
                drawFrame(world, c, side, radiusAt(k), radiusAt(k - 1));

                if (k >= GROW_TICKS) {
                    this.cancel();
                    // 最大の瞬間: 閃光 + 放射状のキラキラ + 音
                    world.spawnParticle(Particle.FLASH, c, 1, 0, 0, 0, 0);
                    world.spawnParticle(Particle.END_ROD, c, 30, 0.5, 0.5, 0.5, 0.14);
                    world.spawnParticle(Particle.ELECTRIC_SPARK, c, 20, 0.6, 0.6, 0.6, 0.2);
                    world.playSound(c, Sound.BLOCK_ANVIL_LAND, 0.8f, 1.9f);
                    world.playSound(c, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 1.2f, 1.0f);
                    if (onPeak != null) onPeak.run();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /** k番目のtickでの半径（出だしが速く、最後に緩やかに最大へ。k=GROW_TICKSで END_RADIUS） */
    private static double radiusAt(int k) {
        double t = Math.min(1.0, Math.max(0.0, (double) k / GROW_TICKS));
        double eased = 1.0 - (1.0 - t) * (1.0 - t);
        return START_RADIUS + (END_RADIUS - START_RADIUS) * eased;
    }

    private static Location centre(Player player, Vector dir, double offset) {
        return player.getLocation().add(0, RING_CENTER_HEIGHT, 0).add(dir.clone().multiply(offset));
    }

    /** 1フレーム分: 外周の円 + 前フレームとの間の補間リング + 内側の薄いリング（壁の厚み感） */
    private static void drawFrame(World world, Location centre, Vector side, double radius, double prevRadius) {
        ring(world, centre, side, radius, 1.0);
        if (prevRadius > 0) ring(world, centre, side, (radius + prevRadius) / 2.0, 0.5);
        if (radius > 0.4) ring(world, centre, side, radius * 0.6, 0.4);
    }

    /** 垂直な円（面内の軸 = side と up）を粒子で描く。density は粒子数の倍率 */
    private static void ring(World world, Location centre, Vector side, double radius, double density) {
        int n = (int) Math.round(2.0 * Math.PI * radius / 0.14 * density);
        n = Math.max(8, Math.min(56, n));
        double phase = Math.random() * (2.0 * Math.PI / n); // フレームごとにずらして「きらめき」感を出す
        for (int i = 0; i < n; i++) {
            double a = phase + 2.0 * Math.PI * i / n;
            double cs = Math.cos(a) * radius;
            double sn = Math.sin(a) * radius;
            Location p = centre.clone().add(side.getX() * cs, sn, side.getZ() * cs);
            world.spawnParticle(Particle.END_ROD, p, 1, 0, 0, 0, 0.01);
            if ((i & 1) == 0) world.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, GOLD);
        }
    }
}
