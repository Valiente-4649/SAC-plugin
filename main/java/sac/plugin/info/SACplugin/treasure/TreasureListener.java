package sac.plugin.info.SACplugin.treasure;

import org.bukkit.ChatColor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import sac.plugin.info.SACplugin.Player.PlayerData;
import sac.plugin.info.SACplugin.SACplugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class TreasureListener implements Listener {
    private final SACplugin plugin;

    // プレイヤーが現在開いている宝箱編集インベントリ → 宝箱ID
    private final Map<UUID, String> openEditors = new HashMap<>();

    public TreasureListener(SACplugin plugin) {
        this.plugin = plugin;
    }

    /** 宝箱エンティティ(チェストのBlockDisplay or 名前のTextDisplay)を見つける共通処理 */
    private String findTreasureId(Entity entity) {
        for (String tag : entity.getScoreboardTags()) {
            if (tag.startsWith("sao_treasure_") && !tag.equals("sao_treasure_chest") && !tag.equals("sao_treasure_label")) {
                return tag.replace("sao_treasure_", "");
            }
        }
        return null;
    }

    // ==========================================
    // ★ 右クリック: 報酬受け取り（全プレイヤー対象）
    // ==========================================
    @EventHandler
    public void onRightClick(PlayerInteractAtEntityEvent event) {
        String treasureId = findTreasureId(event.getRightClicked());
        if (treasureId == null) return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        TreasureData data = plugin.getTreasureManager().getTreasure(treasureId);
        if (data == null) return;

        // OPは右クリックでも報酬受け取りができてしまうと「プレイ上問題なく」の趣旨に反するため、
        // 仕様通りOP/非OP問わず右クリック=報酬受け取りとして扱う（左クリックが編集用）

        // 既に開封済みか確認
        if (plugin.getTreasureManager().hasOpened(treasureId, player.getUniqueId())) {
            player.sendMessage("§b[System] §r§c【宝箱開封済】" + data.name);
            player.sendMessage("§b[System] §r§cこの宝箱は報酬獲得済みです");
            return;
        }

        Inventory treasureInv = plugin.getTreasureManager().getInventory(treasureId);
        if (treasureInv == null) return;

        // 中身のアイテムリストを抽出（空でないスロットのみ）
        java.util.List<ItemStack> rewardItems = new java.util.ArrayList<>();
        for (ItemStack item : treasureInv.getContents()) {
            if (item != null && item.getType() != org.bukkit.Material.AIR) {
                rewardItems.add(item.clone());
            }
        }

        // インベントリの空きを確認（アイテムを実際に追加してみて、余りが出るかチェック）
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(
                rewardItems.toArray(new ItemStack[0])
        );

        if (!leftover.isEmpty()) {
            // 空きが足りなかった場合は、追加してしまった分を取り消して未開封のままにする
            for (ItemStack added : rewardItems) {
                player.getInventory().removeItem(added);
            }
            // leftoverとして返ってきた分も含め、念のため再度全部取り除く処理は上記で完結している
            player.sendMessage("§b[System] §r§e【宝箱回収不可】" + data.name);
            player.sendMessage("§b[System] §r§eインベントリの空きが足りません");
            return;
        }

        // ★ Col・EXP付与
        PlayerData stats = plugin.getPlayerManager().getStats(player);
        if (stats != null) {
            stats.col += data.rewardCol;
            stats.exp += data.rewardExp;

            // ★修正: EXP加算だけではレベルアップ判定がされないため、
            // SAOMobListener.processLevelUpを呼んで必要ならレベルアップさせる
            if (plugin.getSaoMobListener() != null) {
                plugin.getSaoMobListener().processLevelUp(player, stats);
            }

            plugin.getPlayerManager().savePlayerData(player, stats);
            plugin.getSaoScoreboard().updateScoreboard(player, stats);
        }

        // ★ 開封済みとして記録
        plugin.getTreasureManager().markOpened(treasureId, player.getUniqueId());
        // ★追加(実績): 宝箱の開封を記録する(宝箱側の履歴がリセットされても実績の進捗は残る)
        if (plugin.getAchievementManager() != null) plugin.getAchievementManager().onTreasureOpened(player, treasureId);

        // ★ メッセージ表示
        StringBuilder msg = new StringBuilder();
        msg.append("§b【宝箱報酬】").append(data.name).append("\n");
        for (ItemStack item : rewardItems) {
            String itemName = item.hasItemMeta() && item.getItemMeta().hasDisplayName()
                    ? ChatColor.stripColor(item.getItemMeta().getDisplayName())
                    : item.getType().toString();
            msg.append("§b・").append(itemName).append(" ×").append(item.getAmount()).append("\n");
        }
        msg.append("§b・").append(data.rewardCol).append(" Col\n");
        msg.append("§b・").append(data.rewardExp).append(" EXPを獲得しました");

        player.sendMessage(msg.toString());
        player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.3f);
        player.getWorld().spawnParticle(org.bukkit.Particle.HAPPY_VILLAGER, player.getLocation().add(0, 1, 0), 15, 0.4, 0.4, 0.4, 0.1);
    }

    // ==========================================
    // ★ 左クリック: OP限定の中身編集インベントリを開く
    // ==========================================
    @EventHandler
    public void onLeftClick(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player)) return;
        String treasureId = findTreasureId(event.getEntity());
        if (treasureId == null) return;

        event.setCancelled(true);
        Player player = (Player) event.getDamager();

        if (!player.isOp()) {
            player.sendMessage("§b[System] §r§c宝箱の中身編集にはOP権限が必要です。");
            return;
        }

        TreasureData data = plugin.getTreasureManager().getTreasure(treasureId);
        if (data == null) return;

        Inventory treasureInv = plugin.getTreasureManager().getInventory(treasureId);
        if (treasureInv == null) return;

        // 編集用インベントリ（実体を共有して開く。closeイベントで保存する）
        openEditors.put(player.getUniqueId(), treasureId);
        player.openInventory(treasureInv);
    }

    // ==========================================
    // ★ 編集用インベントリのクリック処理
    //   プレイヤー側インベントリのアイテムを右クリック→宝箱側へ
    //   宝箱側インベントリのアイテムを右クリック→プレイヤー側へ
    // ==========================================
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        String treasureId = openEditors.get(player.getUniqueId());
        if (treasureId == null) return;

        // 右クリック以外は通常通り許可しない（誤操作防止のため全クリックをキャンセルしてから手動制御）
        if (event.getClick() != org.bukkit.event.inventory.ClickType.RIGHT) {
            event.setCancelled(true);
            return;
        }

        event.setCancelled(true);

        Inventory clickedInv = event.getClickedInventory();
        if (clickedInv == null) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == org.bukkit.Material.AIR) return;

        Inventory treasureInv = plugin.getTreasureManager().getInventory(treasureId);
        if (treasureInv == null) return;

        boolean clickedTopInventory = clickedInv.equals(treasureInv);

        if (clickedTopInventory) {
            // 宝箱側 → プレイヤー側へ移動
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(clicked.clone());
            if (leftover.isEmpty()) {
                clickedInv.setItem(event.getSlot(), null);
            } else {
                player.sendMessage("§b[System] §r§cインベントリの空きが足りません！");
            }
        } else {
            // プレイヤー側 → 宝箱側へ移動（宝箱は9スロットのみ）
            int emptySlot = -1;
            for (int i = 0; i < treasureInv.getSize(); i++) {
                if (treasureInv.getItem(i) == null || treasureInv.getItem(i).getType() == org.bukkit.Material.AIR) {
                    emptySlot = i;
                    break;
                }
            }
            if (emptySlot == -1) {
                player.sendMessage("§b[System] §r§c宝箱の中身がいっぱいです！");
                return;
            }
            treasureInv.setItem(emptySlot, clicked.clone());
            clickedInv.setItem(event.getSlot(), null);
        }
    }

    // ==========================================
    // ★ 編集インベントリを閉じたら保存する
    // ==========================================
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        Player player = (Player) event.getPlayer();
        String treasureId = openEditors.remove(player.getUniqueId());
        if (treasureId == null) return;

        plugin.getTreasureManager().save(treasureId);
        player.sendMessage("§a[System] 宝箱の中身を保存しました。");
    }
}
