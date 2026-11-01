package sac.plugin.info.SACplugin.Event;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import sac.plugin.info.SACplugin.Player.PlayerData;
import sac.plugin.info.SACplugin.SACplugin;

public class QuitListener implements Listener {
    // 1. メインクラスを保持する変数を定義
    private final SACplugin plugin;

    // 2. コンストラクタで SAOMain を受け取る
    public QuitListener(SACplugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        PlayerData data = plugin.getPlayerManager().getStats(player);

        if (data != null) {
            // ==========================================
            // ★変更: 生死にかかわらず、ログアウト時は常にデータをセーブする！
            // ==========================================
            if (plugin.getVitalityManager() != null) plugin.getVitalityManager().onLogout(player, data);
            plugin.getPlayerManager().savePlayerData(player, data);

            // メモリからは消す
            plugin.getPlayerManager().removePlayer(player);
        }
    }
}
