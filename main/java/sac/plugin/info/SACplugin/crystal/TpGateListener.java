package sac.plugin.info.SACplugin.crystal;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import sac.plugin.info.SACplugin.SACplugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class TpGateListener  implements Listener {
    private final SACplugin plugin;

    public TpGateListener(SACplugin plugin) {
        this.plugin = plugin;
    }

    // ==========================================
    // TPゲートの Interaction エンティティを右クリック
    // ==========================================
    @EventHandler
    public void onGateClick(PlayerInteractAtEntityEvent event) {
        if (!(event.getRightClicked() instanceof org.bukkit.entity.Interaction)) return;
        org.bukkit.entity.Interaction interaction = (org.bukkit.entity.Interaction) event.getRightClicked();
        if (!interaction.getScoreboardTags().contains("sao_tp_gate")) return;

        event.setCancelled(true);
        Player player = event.getPlayer();

        // このゲートのデータを取得
        TpGateManager.TpGateData gate = plugin.getTpGateManager().getGate(interaction.getUniqueId().toString());
        if (gate == null) {
            player.sendMessage("§c[TP] ゲートデータが見つかりません。");
            return;
        }

        // ペアゲートを探す
        TpGateManager.TpGateData pair = plugin.getTpGateManager().findPair(gate);
        if (pair == null) {
            player.sendMessage("§c[TP] 対応する出口ゲートが設置されていません。");
            return;
        }

        // テレポート先を計算
        Location dest = plugin.getTpGateManager().getArrivalLocation(pair);
        if (dest == null) {
            player.sendMessage("§c[TP] 転移先のワールドが見つかりません。");
            return;
        }

        // テレポート
        player.teleport(dest);
    }
}
