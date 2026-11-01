package sac.plugin.info.SACplugin.storage;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.Inventory;
import sac.plugin.info.SACplugin.Player.PlayerData;
import sac.plugin.info.SACplugin.SACplugin;

public class StorageListener implements Listener {
    private final SACplugin plugin;

    public StorageListener(SACplugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        // 右クリック判定
        if (event.getRightClicked() instanceof org.bukkit.entity.Interaction) {
            // ストレージをクリックしたか
            if (event.getRightClicked().getScoreboardTags().contains("sao_storage_station")) {
                event.setCancelled(true);

                Player player = event.getPlayer();
                PlayerData stats = plugin.getPlayerManager().getStats(player);
                if (stats == null) return;

                // 54枠(ラージチェスト)のインベントリを作成
                Inventory inv = Bukkit.createInventory(null, 54, "§b《 Storage 》");

                // プレイヤーのデータに保存されているアイテムを流し込む
                if (stats.storage != null) {
                    inv.setContents(stats.storage);
                }

                player.openInventory(inv);
                player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.5f, 1.0f);
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        // ストレージ画面を閉じた時の処理
        if (event.getView().getTitle().equals("§b《 Storage 》")) {
            Player player = (Player) event.getPlayer();
            PlayerData stats = plugin.getPlayerManager().getStats(player);

            if (stats != null) {
                // インベントリの中身を丸ごとプレイヤーデータに上書き保存
                stats.storage = event.getInventory().getContents();

                // ファイルに書き込む
                plugin.getPlayerManager().savePlayerData(player, stats);

                player.playSound(player.getLocation(), Sound.BLOCK_CHEST_CLOSE, 0.5f, 1.0f);
            }
        }
    }
}
