package sac.plugin.info.SACplugin.daily;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import sac.plugin.info.SACplugin.Player.PlayerData;
import sac.plugin.info.SACplugin.SACplugin;
import sac.plugin.info.SACplugin.npc.SAOQuestData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class DailyNPCListener implements Listener {
    private static final String MENU_TITLE = "§b《本日のデイリー》";

    private final SACplugin plugin;
    private final NamespacedKey dailyPaperFloorKey;

    public DailyNPCListener(SACplugin plugin) {
        this.plugin = plugin;
        this.dailyPaperFloorKey = new NamespacedKey(plugin, "daily_paper_floor");
    }

    // ==========================================
    // デイリーおじさんの右クリック -> メニューを開く
    // ==========================================
    @EventHandler
    public void onDailyNpcInteract(PlayerInteractEntityEvent event) {
        if (plugin.getVitalityManager() != null && plugin.getVitalityManager().isNpcClosed(event.getRightClicked())) return;
        if (event.getRightClicked().getType() != EntityType.VILLAGER) return;
        Villager npc = (Villager) event.getRightClicked();

        int floor = -1;
        for (String tag : npc.getScoreboardTags()) {
            if (tag.startsWith("DAILY_NPC_")) {
                try {
                    floor = Integer.parseInt(tag.substring("DAILY_NPC_".length()));
                } catch (NumberFormatException ignored) {}
                break;
            }
        }
        if (floor == -1) return;

        event.setCancelled(true);
        openDailyMenu(event.getPlayer(), floor);
    }

    private void openDailyMenu(Player player, int floor) {
        Inventory inv = org.bukkit.Bukkit.createInventory(null, 9, MENU_TITLE);

        ItemStack paper = new ItemStack(Material.PAPER);
        ItemMeta meta = paper.getItemMeta();
        meta.setDisplayName("§e今日のデイリー");

        DailyManager.DailyEntry entry = plugin.getDailyManager().getEntry(floor);
        List<String> lore = new ArrayList<>();
        if (entry == null) {
            lore.add("§7本日のデイリーは準備中です。");
        } else {
            switch (entry.type) {
                case KILL:
                    lore.add("§7内容: §f討伐クエスト");
                    break;
                case DELIVERY:
                    lore.add("§7内容: §f納品クエスト");
                    break;
                case TREASURE:
                    lore.add("§7内容: §fこの階層のどこかに宝箱が隠されている");
                    break;
            }
            lore.add("");
            lore.add("§eクリックで詳細確認・受注");
        }
        meta.setLore(lore);
        meta.getPersistentDataContainer().set(dailyPaperFloorKey, PersistentDataType.INTEGER, floor);
        paper.setItemMeta(meta);

        inv.setItem(4, paper);
        player.openInventory(inv);
        player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
    }

    // ==========================================
    // 「今日のデイリー」の紙をクリック -> 種別ごとの処理へ分岐
    // ==========================================
    @EventHandler
    public void onDailyMenuClick(InventoryClickEvent event) {
        if (!event.getView().getTitle().equals(MENU_TITLE)) return;
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;

        Integer floorObj = clicked.getItemMeta().getPersistentDataContainer()
                .get(dailyPaperFloorKey, PersistentDataType.INTEGER);
        if (floorObj == null) return;
        int floor = floorObj;

        Player player = (Player) event.getWhoClicked();
        DailyManager.DailyEntry entry = plugin.getDailyManager().getEntry(floor);

        if (entry == null) {
            player.sendMessage("§b[System] §r§c本日のデイリーはまだ準備中のようだ。");
            player.closeInventory();
            return;
        }

        if (entry.type == DailyManager.DailyType.TREASURE) {
            player.closeInventory();
            player.sendMessage("§6[System] §f第" + floor + "層のどこかに、今日だけの宝箱が隠されているらしい……");
            player.sendMessage("§b[System] §r§7探し出して開けてみよう！");
            player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
            return;
        }

        // --- 討伐・納品タイプ: 通常のクエスト受注/進行/報告処理を流用する ---
        PlayerData stats = plugin.getPlayerManager().getStats(player);
        if (stats == null) return;

        String dailyId = entry.getQuestId();
        SAOQuestData quest = plugin.getQuestManager().getQuest(dailyId);

        if (quest == null) {
            player.sendMessage("§b[System] §r§c本日のデイリーはまだ準備中のようだ。");
            player.closeInventory();
            return;
        }

        String today = plugin.getDailyManager().getLogicalDateString();
        String lastClear = stats.lastDailyClearDate;
        boolean alreadyClearedToday = today.equals(lastClear);

        // 既に本日どこかの階層でデイリーをクリア済みで、かつ今まさにそのデイリーを進行中というわけでもない場合は受注させない
        if (alreadyClearedToday && !dailyId.equals(stats.currentQuestId)) {
            player.sendMessage("§b[System] §r§c本日のデイリーは既にクリア済みです。明日の4時以降にまた挑戦してください。");
            player.closeInventory();
            return;
        }

        player.closeInventory();
        plugin.getQuestListener().processQuestInteraction(player, quest, quest.npcName);
    }

    // ==========================================
    // デイリー宝箱(実際のチェストブロック)を開けた時の処理
    // ==========================================
    @EventHandler
    public void onChestInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getClickedBlock() == null || event.getClickedBlock().getType() != Material.CHEST) return;

        DailyManager.DailyEntry entry = plugin.getDailyManager().getEntryByChestLocation(event.getClickedBlock().getLocation());
        if (entry == null) return; // デイリー宝箱ではない普通のチェストは無視

        event.setCancelled(true); // 中身が空の実チェストとして開かせない（報酬は仮想的に付与する）

        Player player = event.getPlayer();
        PlayerData stats = plugin.getPlayerManager().getStats(player);
        if (stats == null) return;

        String today = plugin.getDailyManager().getLogicalDateString();
        String lastClear = stats.lastDailyClearDate;
        if (today.equals(lastClear)) {
            player.sendMessage("§b[System] §r§7本日はすでにどこかでデイリーをクリア済みのようだ……(明日また来よう)");
            player.playSound(player.getLocation(), Sound.BLOCK_CHEST_LOCKED, 1.0f, 1.0f);
            return;
        }

        // 報酬付与
        stats.col += entry.rewardCol;
        stats.exp += entry.rewardExp;
        player.sendMessage("§6[デイリー宝箱] §fCol " + entry.rewardCol + " / EXP " + entry.rewardExp + " を手に入れた！");

        plugin.getDailyManager().grantRewardItems(player, entry);

        stats.lastDailyClearDate = today;

        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 1.0f, 1.0f);
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
        plugin.getPlayerManager().savePlayerData(player, stats);
    }
}