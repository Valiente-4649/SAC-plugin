package sac.plugin.info.SACplugin.Event;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import sac.plugin.info.SACplugin.Player.PlayerData;
import sac.plugin.info.SACplugin.SACplugin;

public class HealthListener implements Listener {
    private final SACplugin plugin;

    public HealthListener(SACplugin plugin) {
        this.plugin = plugin;
    }

    // 自然回復やポーションで回復した時の処理
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeal(EntityRegainHealthEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();
        PlayerData data = plugin.getPlayerManager().getStats(player);
        if (data == null) return;

        // --- 回復量のブースト計算 ---
        // バニラの回復量(通常0.5)を無視して、最大HPの2%を回復させる
        double boostAmount = data.maxHP * 0.02;

        // 独自HPに加算
        data.currentHP += boostAmount;

        // 最大値を超えないように制限
        if (data.currentHP > data.maxHP) data.currentHP = data.maxHP;

        // ハートの見た目（割合）を更新
        double visualHealth = (data.currentHP / data.maxHP) * 20.0;
        player.setHealth(Math.min(20.0, Math.max(1.0, visualHealth)));

        // バニラ本来の微小な回復はキャンセル
        event.setCancelled(true);

        // スコアボードの数値を即座に更新
        plugin.getSaoScoreboard().updateScoreboard(player, data);
    }
}

