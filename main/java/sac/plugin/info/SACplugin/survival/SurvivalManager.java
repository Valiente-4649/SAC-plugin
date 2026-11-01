package sac.plugin.info.SACplugin.survival;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import sac.plugin.info.SACplugin.Player.PlayerData;
import sac.plugin.info.SACplugin.SACplugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * ★新規: バニラの空腹システムを置き換える独自の「満腹ゲージ」と、
 * それに紐づくHP回復(転移門周辺の自動回復・ポーションのゆっくり回復)、
 * 空腹時のダメージを一括管理するマネージャー。
 *
 * - 満腹ゲージの減少判定: 5秒おき (動いていれば1%減少、止まっていれば3回連続=15秒で1%減少)
 * - セーフゾーン(転移門周辺)回復・空腹ダメージ: 1秒おき
 * - ポーションのゆっくり回復: startGradualHeal()で開始、1秒おきに指定量ずつ回復
 */
public class SurvivalManager {
    private final SACplugin plugin;

    // 満腹度が下がるほど移動速度が低下する閾値(SAOAGIListenerが参照する)
    public static final double HUNGER_SPEED_THRESHOLD_30 = 30.0; // -20%
    public static final double HUNGER_SPEED_THRESHOLD_20 = 20.0; // -40%
    public static final double HUNGER_SPEED_THRESHOLD_10 = 10.0; // -60%

    // セーフゾーン(転移門)の効果半径と回復量
    private static final double SAFE_ZONE_RADIUS = 20.0;
    private static final double SAFE_ZONE_HEAL_PERCENT_PER_SEC = 0.05; // maxHPの5%/秒

    // 空腹ダメージ: hunger<=0の間、10秒に1回、maxHPの5%
    private static final int STARVE_DAMAGE_INTERVAL_SEC = 10;
    private static final double STARVE_DAMAGE_PERCENT = 0.05;

    // 5秒おき満腹度チェック用: 直前チェック時の座標
    private final Map<UUID, Location> lastCheckLocation = new HashMap<>();
    // 「静止している」と判定された回数の連続カウント(3回=15秒で1%減少)
    private final Map<UUID, Integer> stillStreak = new HashMap<>();
    // 空腹ダメージの経過秒数カウンタ
    private final Map<UUID, Integer> starveTickCounter = new HashMap<>();
    // ポーション等による「ゆっくり回復」の進行中タスク(1人1つまで、後勝ち)
    private final Map<UUID, BukkitTask> gradualHealTasks = new HashMap<>();

    public SurvivalManager(SACplugin plugin) {
        this.plugin = plugin;
    }

    /** onEnableから呼び出し、定期タスクを開始する */
    public void start() {
        // 1秒ごと: セーフゾーン回復 + 空腹ダメージ判定
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    try {
                        tickPerSecond(player);
                    } catch (Exception e) {
                        plugin.getLogger().warning("[Survival] " + player.getName() + " のtickPerSecondで例外: " + e.getMessage());
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);

        // 5秒ごと: 満腹ゲージの減少判定
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    try {
                        tickHungerDecay(player);
                    } catch (Exception e) {
                        plugin.getLogger().warning("[Survival] " + player.getName() + " のtickHungerDecayで例外: " + e.getMessage());
                    }
                }
            }
        }.runTaskTimer(plugin, 400L, 400L);
    }

    // ==========================================
    // 1秒ごとの処理: セーフゾーン回復 + 空腹ダメージ
    // ==========================================
    private void tickPerSecond(Player player) {
        PlayerData stats = plugin.getPlayerManager().getStats(player);
        if (stats == null || stats.isDead) return;

        UUID uuid = player.getUniqueId();
        boolean changed = false;

        // ★転移門周辺: 1秒ごとにmaxHPの5%回復
        if (stats.currentHP < stats.maxHP && isNearSafeZone(player)) {
            double heal = stats.maxHP * SAFE_ZONE_HEAL_PERCENT_PER_SEC;
            stats.currentHP = Math.min(stats.maxHP, stats.currentHP + heal);
            changed = true;
        }

        // ★空腹ダメージ: 満腹度0%の間、10秒に1回maxHPの5%ダメージ
        if (stats.hunger <= 0.0) {
            int count = starveTickCounter.getOrDefault(uuid, 0) + 1;
            if (count >= STARVE_DAMAGE_INTERVAL_SEC) {
                stats.currentHP -= stats.maxHP * STARVE_DAMAGE_PERCENT;
                changed = true;
                starveTickCounter.put(uuid, 0);
            } else {
                starveTickCounter.put(uuid, count);
            }
        } else {
            starveTickCounter.put(uuid, 0);
        }

        if (!changed) return;

        if (stats.currentHP <= 0) {
            stats.currentHP = 0;
            player.setHealth(0); // 死亡処理はDeathListener側の既存フローに任せる
            plugin.getPlayerManager().savePlayerData(player, stats);
            return;
        }

        plugin.getPlayerManager().applyVisualHealth(player, stats);
        plugin.getPlayerManager().savePlayerData(player, stats);
        plugin.getSaoScoreboard().updateScoreboard(player, stats);
    }

    // ==========================================
    // 5秒ごとの処理: 満腹ゲージの減少判定
    // 動いていれば1%減少、止まっていれば3回連続(15秒)で1%減少。
    // ==========================================
    private void tickHungerDecay(Player player) {
        PlayerData stats = plugin.getPlayerManager().getStats(player);
        if (stats == null || stats.isDead) return;

        UUID uuid = player.getUniqueId();
        Location current = player.getLocation();
        Location last = lastCheckLocation.get(uuid);
        lastCheckLocation.put(uuid, current.clone());

        // 初回チェックは基準座標を記録するだけで判定しない
        if (last == null) return;

        boolean moved = last.getWorld() != null && last.getWorld().equals(current.getWorld())
                && last.distanceSquared(current) > 0.09; // 0.3ブロック超の移動を「動いた」とみなす

        boolean shouldDecay;
        if (moved) {
            shouldDecay = true;
            stillStreak.put(uuid, 0);
        } else {
            int streak = stillStreak.getOrDefault(uuid, 0) + 1;
            shouldDecay = streak >= 6;
            stillStreak.put(uuid, shouldDecay ? 0 : streak);
        }

        if (!shouldDecay) return;

        double decayAmount = 1.0;
        double beforeHunger = stats.hunger;
        double beforeBuffer = stats.satietyBuffer;

        // ==========================================
        // ★追加: 「腹持ち%」の隠しバッファを優先消費する。
        // バッファが残っている間は満腹度(hunger)自体は減らさない。
        // バッファが減少量に満たない場合は、余った分だけ満腹度に食い込ませる。
        // ==========================================
        if (stats.satietyBuffer > 0.0) {
            if (stats.satietyBuffer >= decayAmount) {
                stats.satietyBuffer = Math.max(0.0, stats.satietyBuffer - decayAmount);
            } else {
                double remainder = decayAmount - stats.satietyBuffer;
                stats.satietyBuffer = 0.0;
                stats.hunger = Math.max(0.0, stats.hunger - remainder);
            }
        } else {
            stats.hunger = Math.max(0.0, stats.hunger - decayAmount);
        }

        if (stats.hunger == beforeHunger && stats.satietyBuffer == beforeBuffer) return;

        plugin.getPlayerManager().savePlayerData(player, stats);
        plugin.getPlayerManager().syncFoodLevelBar(player, stats);
        plugin.getSaoScoreboard().updateScoreboard(player, stats);

        // 満腹度の閾値を跨いだ可能性があるため移動速度を再計算する
        try {
            plugin.getAgiListener().updateMovementSpeed(player);
        } catch (Exception e) {
            plugin.getLogger().warning("[Survival] " + player.getName() + " のupdateMovementSpeedで例外: " + e.getMessage());
        }
    }

    /** プレイヤーが転移門(街)またはカスタムセーフゾームから一定範囲内にいるかどうか */
    private boolean isNearSafeZone(Player player) {
        Location loc = player.getLocation();

        for (Location tp : plugin.getCrystalManager().getAllTpLocations()) {
            if (tp.getWorld() == null || !tp.getWorld().equals(loc.getWorld())) continue;
            if (tp.distanceSquared(loc) <= SAFE_ZONE_RADIUS * SAFE_ZONE_RADIUS) return true;
        }

        // ★追加: OPが設置したカスタムセーフゾーン(/safezone)も判定に含める
        for (sac.plugin.info.SACplugin.survival.SafeZoneData zone : plugin.getSafeZoneManager().getAllZones()) {
            org.bukkit.World w = Bukkit.getWorld(zone.world);
            if (w == null || !w.equals(loc.getWorld())) continue;
            Location zoneLoc = new Location(w, zone.x, zone.y, zone.z);
            if (zoneLoc.distanceSquared(loc) <= zone.radius * zone.radius) return true;
        }

        return false;
    }

    // ==========================================
    // ★追加: ポーション等による「時間経過でのゆっくり回復」を開始する。
    // 既に進行中の回復がある場合はキャンセルして新しいものに差し替える(後勝ち)。
    // ==========================================
    public void startGradualHeal(Player player, double totalAmount, double durationSeconds) {
        UUID uuid = player.getUniqueId();
        BukkitTask existing = gradualHealTasks.remove(uuid);
        if (existing != null) existing.cancel();

        int totalTicks = Math.max(1, (int) Math.round(durationSeconds));
        double perTick = totalAmount / totalTicks;

        BukkitTask task = new BukkitRunnable() {
            int remainingTicks = totalTicks;

            @Override
            public void run() {
                PlayerData stats = plugin.getPlayerManager().getStats(player);
                if (stats == null || !player.isOnline() || stats.isDead) {
                    gradualHealTasks.remove(uuid);
                    cancel();
                    return;
                }

                stats.currentHP = Math.min(stats.maxHP, stats.currentHP + perTick);
                plugin.getPlayerManager().applyVisualHealth(player, stats);
                plugin.getPlayerManager().savePlayerData(player, stats);
                plugin.getSaoScoreboard().updateScoreboard(player, stats);

                remainingTicks--;
                if (remainingTicks <= 0 || stats.currentHP >= stats.maxHP) {
                    gradualHealTasks.remove(uuid);
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);

        gradualHealTasks.put(uuid, task);
    }

    /** ログアウト時の後片付け(メモリリーク防止)。QuitListenerから呼び出すことを推奨。 */
    public void handleQuit(Player player) {
        UUID uuid = player.getUniqueId();
        lastCheckLocation.remove(uuid);
        stillStreak.remove(uuid);
        starveTickCounter.remove(uuid);
        BukkitTask task = gradualHealTasks.remove(uuid);
        if (task != null) task.cancel();
    }
}