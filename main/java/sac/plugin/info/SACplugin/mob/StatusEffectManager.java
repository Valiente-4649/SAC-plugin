package sac.plugin.info.SACplugin.mob;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.scheduler.BukkitTask;
import sac.plugin.info.SACplugin.Player.PlayerData;
import sac.plugin.info.SACplugin.SACplugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * ★新規: 麻痺・毒の状態異常を管理する。
 * - 麻痺: レベル1〜5(=1〜5秒間)、その間プレイヤーは移動できなくなる(視点移動は可能)。
 * - 毒: レベルなし、指定秒数の間1秒ごとに最大HPの3%を失う。
 */
public class StatusEffectManager implements Listener {
    private final SACplugin plugin;

    // ★スタン(旧麻痺システム。解毒ポーション無効)
    private static final Map<UUID, Long> stunnedUntil = new HashMap<>();
    // ★麻痺(新システム。解毒ポーションで解除可能)
    private static final Map<UUID, Long> paralyzedUntil = new HashMap<>();
    // プレイヤーUUID -> 現在進行中の毒ダメージタスク(重複付与時に古い方を止めて上書きするため)
    private static final Map<UUID, BukkitTask> poisonTasks = new HashMap<>();

    // ★麻痺(新)のレベルごとの持続秒数
    private static final int[] PARALYZE_SECONDS = {0, 15, 20, 25, 30, 35}; // index=level

    public StatusEffectManager(SACplugin plugin) {
        this.plugin = plugin;
    }

    public boolean isStunned(Player player) {
        Long until = stunnedUntil.get(player.getUniqueId());
        return until != null && System.currentTimeMillis() < until;
    }

    public boolean isParalyzed(Player player) {
        Long until = paralyzedUntil.get(player.getUniqueId());
        return until != null && System.currentTimeMillis() < until;
    }

    /** スタン・麻痺のどちらかにかかっていて、移動・攻撃ができない状態かどうか */
    public boolean isActionLocked(Player player) {
        return isStunned(player) || isParalyzed(player);
    }

    /** スタンを付与する(旧・麻痺システム。解毒ポーションでは解除できない)。レベル=秒数。 */
    public void applyStun(Player player, int level) {
        if (level <= 0) return;
        UUID uuid = player.getUniqueId();
        long newUntil = System.currentTimeMillis() + (level * 1000L);
        Long current = stunnedUntil.get(uuid);
        if (current != null && current > newUntil) return;
        stunnedUntil.put(uuid, newUntil);

        player.sendMessage("§c[スタン] §f" + level + "秒間、身動きが取れなくなった！");
        player.playSound(player.getLocation(), Sound.ENTITY_WITCH_HURT, 1.0f, 0.5f);
        player.getWorld().spawnParticle(Particle.CRIT, player.getLocation().add(0, 1, 0), 15, 0.3, 0.5, 0.3, 0.01);
    }

    /** 麻痺(新)を付与する。レベル1〜5で15/20/25/30/35秒間。解毒ポーションで解除可能。 */
    public void applyParalysis(Player player, int level) {
        if (level <= 0) return;
        level = Math.min(level, PARALYZE_SECONDS.length - 1);
        UUID uuid = player.getUniqueId();
        int seconds = PARALYZE_SECONDS[level];
        long newUntil = System.currentTimeMillis() + (seconds * 1000L);
        Long current = paralyzedUntil.get(uuid);
        if (current != null && current > newUntil) return;
        paralyzedUntil.put(uuid, newUntil);

        player.sendMessage("§5[麻痺] §f" + seconds + "秒間動けなくなった！(解毒ポーションで解除できます)");
        player.playSound(player.getLocation(), Sound.ENTITY_WITCH_HURT, 1.0f, 0.7f);
        player.getWorld().spawnParticle(Particle.WITCH, player.getLocation().add(0, 1, 0), 15, 0.3, 0.5, 0.3, 0.01);
    }

    /**
     * 麻痺(新)を解毒ポーションで解除する。スタンには一切効果がない。
     * @return 実際に麻痺が解除されたらtrue(何も無かった場合はfalse)
     */
    public boolean cureParalysis(Player player) {
        UUID uuid = player.getUniqueId();
        if (!isParalyzed(player)) return false;
        paralyzedUntil.remove(uuid);
        player.sendMessage("§a[解毒] §f麻痺が解除された！");
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.5f);
        return true;
    }

    /**
     * 毒を解毒ポーションで解除する。
     * @return 実際に毒が解除されたらtrue(何も無かった場合はfalse)
     */
    public boolean curePoison(Player player) {
        UUID uuid = player.getUniqueId();
        BukkitTask task = poisonTasks.remove(uuid);
        if (task == null) return false;
        task.cancel();
        player.sendMessage("§a[解毒] §f毒が解除された！");
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.5f);
        return true;
    }

    /** 毒を付与する。既に毒状態なら古いタスクを止めて指定秒数で上書きする。 */
    public void applyPoison(Player player, double durationSeconds) {
        if (durationSeconds <= 0) return;
        UUID uuid = player.getUniqueId();

        BukkitTask existing = poisonTasks.remove(uuid);
        if (existing != null) existing.cancel();

        player.sendMessage("§b[System] §r§2毒状態(" + durationSeconds + "秒間)");
        player.playSound(player.getLocation(), Sound.ENTITY_WITCH_DRINK, 1.0f, 1.0f);

        int totalTicksOfPoison = (int) Math.round(durationSeconds);
        int[] elapsed = {0};

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline()) {
                BukkitTask t = poisonTasks.remove(uuid);
                if (t != null) t.cancel();
                return;
            }

            PlayerData stats = plugin.getPlayerManager().getStats(player);
            if (stats != null) {
                double poisonDamage = stats.maxHP * 0.03;
                stats.currentHP = Math.max(0, stats.currentHP - poisonDamage);

                if (stats.currentHP <= 0) {
                    player.setHealth(0);
                } else {
                    double visualHealth = (stats.currentHP / stats.maxHP) * 20.0;
                    player.setHealth(Math.min(20.0, Math.max(1.0, visualHealth)));
                }
                if (plugin.getSaoScoreboard() != null) plugin.getSaoScoreboard().updateScoreboard(player, stats);

                player.getWorld().spawnParticle(Particle.SNEEZE, player.getLocation().add(0, 1, 0), 8, 0.2, 0.3, 0.2, 0.01);
            }

            elapsed[0]++;
            if (elapsed[0] >= totalTicksOfPoison) {
                BukkitTask t = poisonTasks.remove(uuid);
                if (t != null) t.cancel();
            }
        }, 20L, 20L);

        poisonTasks.put(uuid, task);
    }

    // ==========================================
    // ★スタン・麻痺中の移動ロック(釣り中の移動ロックと同じ方式。視点移動のみ許可)
    // ==========================================
    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!isActionLocked(player)) return;

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to != null && (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ())) {
            Location newLoc = from.clone();
            newLoc.setYaw(to.getYaw());
            newLoc.setPitch(to.getPitch());
            event.setTo(newLoc);
        }
    }
}