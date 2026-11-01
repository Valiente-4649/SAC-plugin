package sac.plugin.info.SACplugin.party;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import sac.plugin.info.SACplugin.SACplugin;

public class PartyChatListener implements Listener {
    private final SACplugin plugin;

    public PartyChatListener(SACplugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        PartyManager pm = plugin.getPartyManager();

        // もしパーティーに参加していて、かつチャットがオンになっている場合
        if (pm.isInParty(player) && pm.isPartyChatEnabled(player)) {
            // サーバー全体チャットには流さないようにキャンセルする
            event.setCancelled(true);
            // パーティーメンバーだけにメッセージを送る
            pm.sendPartyMessage(player, event.getMessage());
        } else {
            // ==========================================
            // ★追加: 通常チャットのフォーマットを強制的に上書きする！
            // これで、頭上のHPバー(TeamのSuffix)がチャット欄に混ざるのを防ぎます
            // ==========================================
            // 見た目: <プレイヤー名> メッセージ (バニラと同じ見た目です)
            event.setFormat("<" + player.getName() + "> %2$s");
        }
    }
}
