package sac.plugin.info.SACplugin.Event;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import sac.plugin.info.SACplugin.Player.PlayerData;
import sac.plugin.info.SACplugin.SACplugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SAOStepListener implements Listener {
    private final SACplugin plugin;

    // ステップのクールダウン管理 (基本3秒。AGI3につき0.1秒短縮、最低0.5秒)
    private final Map<UUID, Long> stepCooldowns = new HashMap<>();

    // ★追加: ステップクールタイムの基本値・減衰計算用の定数
    private static final long STEP_COOLDOWN_BASE_MS = 3000L;   // 基本クールタイム(3秒)
    private static final int AGI_PER_REDUCTION = 3;             // このAGI数値ごとに
    private static final long REDUCTION_MS = 100L;              // 0.1秒(100ms)短縮
    private static final long STEP_COOLDOWN_MIN_MS = 500L;      // 最低保証クールタイム(0.5秒)

    // ダブルタップ(連続スニーク)を検知するための時間記録
    private final Map<UUID, Long> lastSneakTime = new HashMap<>();

    // ==========================================
    // ★追加: プレイヤーが「今どっちに歩いているか」を記録する
    // ==========================================
    private final Map<UUID, Vector> lastMoveDirection = new HashMap<>();
    private final Map<UUID, Long> lastMoveTime = new HashMap<>();

    public SAOStepListener(SACplugin plugin) {
        this.plugin = plugin;
    }

    // ==========================================
    // ★新規追加: 座標の動きからWASDの方向を計算する
    // ==========================================
    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;

        // X, Z 座標（平面）が動いたかチェック (視点を動かしただけなら無視)
        if (from.getX() != to.getX() || from.getZ() != to.getZ()) {
            // 動いた方向(ベクトル)を計算
            Vector dir = to.toVector().subtract(from.toVector()).setY(0);

            if (dir.lengthSquared() > 0.0001) { // ごくわずかなブレを無視
                Player player = event.getPlayer();
                UUID uuid = player.getUniqueId();
                // 動いている方向と、その時間を記録
                lastMoveDirection.put(uuid, dir.normalize());
                lastMoveTime.put(uuid, System.currentTimeMillis());
            }
        }
    }

    @EventHandler
    public void onPlayerSneak(PlayerToggleSneakEvent event) {
        if (!event.isSneaking()) return;

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR) return;

        long currentTime = System.currentTimeMillis();

        // ダブルタップ判定 (0.3秒以内に2回Shift)
        long lastTime = lastSneakTime.getOrDefault(uuid, 0L);

        if (currentTime - lastTime > 300) {
            lastSneakTime.put(uuid, currentTime);
            return;
        }

        lastSneakTime.put(uuid, 0L);

        // クールダウン判定
        if (stepCooldowns.containsKey(uuid)) {
            long timeLeft = stepCooldowns.get(uuid) - currentTime;
            if (timeLeft > 0) return;
        }

        // ==========================================
        // ★修正: Velocityではなく、先ほど計算した「実際の移動方向」を使う！
        // ==========================================
        long lastMove = lastMoveTime.getOrDefault(uuid, 0L);

        // 最後に動いてから0.2秒以上経っていたら「立ち止まっている」と判定してステップしない
        if (currentTime - lastMove > 200) return;

        Vector stepDir = lastMoveDirection.get(uuid);
        if (stepDir == null) return;

        // GrimACの一時的な無効化処理 (1秒間)
        PermissionAttachment attachment = player.addAttachment(plugin, "grim.exempt", true);
        new BukkitRunnable() {
            @Override
            public void run() {
                if (player.isOnline()) {
                    player.removeAttachment(attachment);
                }
            }
        }.runTaskLater(plugin, 20L);

        double stepPower = 1.2;

        Vector stepVector = stepDir.clone().multiply(stepPower);
        stepVector.setY(0.3);

        player.setVelocity(stepVector);
        player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 0.5f, 1.5f);
        player.getWorld().spawnParticle(org.bukkit.Particle.CLOUD, player.getLocation().add(0, 0.1, 0), 10, 0.3, 0.1, 0.3, 0.1);

        stepCooldowns.put(uuid, currentTime + calculateStepCooldown(player));
    }

    // ==========================================
    // ★追加: AGIに応じたステップクールタイムを計算する
    // 基本3秒から、AGI3につき0.1秒短縮(最低0.5秒)
    // ==========================================
    public long calculateStepCooldown(Player player) {
        PlayerData stats = plugin.getPlayerManager().getStats(player);
        // ★修正: 防具のAGIボーナス(金属防具鍛造)が反映されていなかったため合算する
        int agi = (stats != null) ? stats.agi + stats.armorAgiBonus : 0;

        long reduction = (agi / AGI_PER_REDUCTION) * REDUCTION_MS;
        long cooldown = STEP_COOLDOWN_BASE_MS - reduction;

        return Math.max(STEP_COOLDOWN_MIN_MS, cooldown);
    }
}