package sac.plugin.info.SACplugin.farming;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import sac.plugin.info.SACplugin.Player.PlayerData;
import sac.plugin.info.SACplugin.SACplugin;

import java.util.*;

public class SAOFarmMenuListener implements Listener {
    private final SACplugin plugin;
    // ★ farmId を UUID ではなく座標文字列("worldName:x:y:z")で保持する
    private final Map<UUID, String> viewingFarms = new HashMap<>();

    public SAOFarmMenuListener(SACplugin plugin) { this.plugin = plugin; }

    // ★ 引数を UUID → Entity に変更。座標文字列の farmId を生成してから保存する
    public void openFarmMenu(Player player, org.bukkit.entity.Entity farmEntity) {
        String farmId = SAOFarmManager.toFarmId(farmEntity);
        viewingFarms.put(player.getUniqueId(), farmId);
        Inventory inv = Bukkit.createInventory(null, 27, "§b《 農地契約メニュー 》");
        SAOFarmManager.FarmData farm = plugin.getFarmManager().getFarm(farmId);

        // 1. 契約ステータス (スロット10)
        ItemStack statusBtn;
        if (farm == null) {
            statusBtn = createItem(Material.PAPER, "§a農地を契約する", Arrays.asList(
                    "§7500 Col を支払うことで、", "§732日間この農地を借りることができます。", "",
                    "§c※注意: §7種が一つも植えられていない状態が", "§78日間続くと強制解約されます！", "", "§e▶ クリックして契約する (500 Col)"
            ));
        } else if (farm.ownerUuid.equals(player.getUniqueId().toString())) {
            List<String> lore = new ArrayList<>();
            lore.add("§7残り契約期間: §a" + farm.remainingDays + " 日");
            lore.add("§7(未稼働日数: " + farm.emptyDays + " 日)");
            lore.add("");
            if (farm.remainingDays <= 8) lore.add("§e▶ クリックして期間を32日延長する (500 Col)");
            else lore.add("§8(残り8日以下になると延長できます)");
            lore.add("§c※日数はSAC鯖の3時間の昼夜サイクルを基準とします！");
            lore.add("");
            lore.add("§c▶ Shift + クリック で強制解約する");
            statusBtn = createItem(Material.WRITTEN_BOOK, "§a契約状況", lore);
        } else {
            statusBtn = createItem(Material.BARRIER, "§c他人の農地です", Arrays.asList("§7所有者: " + farm.ownerName));
        }
        inv.setItem(10, statusBtn);

        // 2. 種の購入 (スロット12)
        inv.setItem(12, createItem(Material.WHEAT_SEEDS, "§6種の購入", Arrays.asList("§7クリックで種販売メニューを開きます")));

        // 3. ジョウロの購入 (スロット14)
        inv.setItem(14, createItem(Material.IRON_NUGGET, "§bジョウロの購入", Arrays.asList("§7価格: 500 Col", "§e▶ クリックで購入する")));

        // 4. 肥料の購入 (スロット16)
        long fertPrice = 100;
        sac.plugin.info.SACplugin.item.SAOGeneralItemData fData = plugin.getSaoItemManager().getGeneralItem("fertilizer");
        if (fData != null && fData.price > 0) fertPrice = fData.price;
        inv.setItem(16, createItem(Material.BONE_MEAL, "§a肥料の購入", Arrays.asList("§7価格: " + fertPrice + " Col", "§e▶ クリックで購入する")));

        player.openInventory(inv);
    }

    // ★ 種販売専用メニュー
    public void openSeedShop(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, "§b《 種の購入ショップ 》");
        Map<String, SAOFarmManager.CropData> allCrops = plugin.getFarmManager().getAllCrops();

        for (SAOFarmManager.CropData crop : allCrops.values()) {
            ItemStack seed = new ItemStack(Material.MELON_SEEDS);
            ItemMeta meta = seed.getItemMeta();
            meta.setDisplayName("§b" + crop.name);
            meta.setLore(Arrays.asList("§8" + crop.id, "§7価格: §e" + crop.price + " Col", "", "§f" + crop.description, "", "§e▶ クリックで購入"));
            seed.setItemMeta(meta);
            inv.addItem(seed);
        }
        inv.setItem(26, createItem(Material.BARRIER, "§c戻る", Arrays.asList("§7契約メニューに戻ります")));
        player.openInventory(inv);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        String title = event.getView().getTitle();

        // このプラグインのメニュー以外は無視
        if (!title.equals("§b《 農地契約メニュー 》") && !title.equals("§b《 種の購入ショップ 》")) return;

        // ★ このプラグインのメニューは全クリックをキャンセル（アイテム移動を完全防止）
        event.setCancelled(true);

        String farmId = viewingFarms.get(player.getUniqueId());
        if (farmId == null) return;

        PlayerData stats = plugin.getPlayerManager().getStats(player);

        if (title.equals("§b《 農地契約メニュー 》")) {
            int slot = event.getRawSlot();
            // プレイヤーインベントリ側(スロット27以上)のクリックは無視
            if (slot >= 27) return;

            SAOFarmManager.FarmData farm = plugin.getFarmManager().getFarm(farmId);

            if (slot == 10) {
                if (farm == null) {
                    if (stats.col >= 500) {
                        stats.col -= 500;
                        SAOFarmManager.FarmData newData = new SAOFarmManager.FarmData();
                        newData.ownerUuid = player.getUniqueId().toString();
                        newData.ownerName = player.getName();
                        newData.worldName = player.getWorld().getName();
                        newData.remainingDays = 32;
                        plugin.getFarmManager().addFarm(farmId, newData);
                        plugin.getPlayerManager().savePlayerData(player, stats);
                        if (plugin.getSaoScoreboard() != null) plugin.getSaoScoreboard().updateScoreboard(player, stats);
                        player.sendMessage("§a[System] 農地を契約しました！");
                        // ★ メニューを再表示するため farmId から Entity を探して openFarmMenu を呼ぶ
                        reopenFarmMenu(player, farmId);
                    } else {
                        player.sendMessage("§b[System] §r§cINSUFFICIENT COL");
                    }
                } else if (farm.ownerUuid.equals(player.getUniqueId().toString())) {
                    if (event.isShiftClick()) {
                        // ★ 解約処理。setCancelled(true) は既に済んでいるので本は入らない
                        plugin.getFarmManager().resetPhysicalFarm(farmId);
                        plugin.getFarmManager().removeFarm(farmId);
                        player.sendMessage("§c[System] 農地を解約し、畑を更地に戻しました。");
                        player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                        player.closeInventory();
                    } else if (farm.remainingDays <= 8 && stats.col >= 500) {
                        stats.col -= 500;
                        farm.remainingDays += 32;
                        plugin.getFarmManager().saveFarms();
                        plugin.getPlayerManager().savePlayerData(player, stats);
                        if (plugin.getSaoScoreboard() != null) plugin.getSaoScoreboard().updateScoreboard(player, stats);
                        player.sendMessage("§a[System] 契約を延長しました！");
                        reopenFarmMenu(player, farmId);
                    }
                }
            } else if (slot == 12) {
                player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                openSeedShop(player);
            } else if (slot == 14) {
                sac.plugin.info.SACplugin.item.SAOGeneralItemData gData = plugin.getSaoItemManager().getGeneralItem("watering_can");
                if (gData != null && stats.col >= 500) {
                    stats.col -= 500;
                    player.getInventory().addItem(plugin.getSaoItemManager().createGeneralItem(gData));
                    plugin.getPlayerManager().savePlayerData(player, stats);
                    if (plugin.getSaoScoreboard() != null) plugin.getSaoScoreboard().updateScoreboard(player, stats);
                    player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                } else if (gData != null) {
                    player.sendMessage("§b[System] §r§cINSUFFICIENT COL");
                }
            } else if (slot == 16) {
                sac.plugin.info.SACplugin.item.SAOGeneralItemData fData = plugin.getSaoItemManager().getGeneralItem("fertilizer");
                if (fData != null) {
                    long price = fData.price > 0 ? fData.price : 100;
                    if (stats.col >= price) {
                        stats.col -= price;
                        player.getInventory().addItem(plugin.getSaoItemManager().createGeneralItem(fData));
                        plugin.getPlayerManager().savePlayerData(player, stats);
                        if (plugin.getSaoScoreboard() != null) plugin.getSaoScoreboard().updateScoreboard(player, stats);
                        player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                    } else {
                        player.sendMessage("§b[System] §r§cINSUFFICIENT COL");
                    }
                } else {
                    player.sendMessage("§b[System] §r§c肥料のデータが設定されていません。");
                }
            }
        } else if (title.equals("§b《 種の購入ショップ 》")) {
            int slot = event.getRawSlot();
            if (slot >= 27) return;

            if (slot == 26) {
                reopenFarmMenu(player, farmId);
                return;
            }
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || !clicked.hasItemMeta() || !clicked.getItemMeta().hasLore()) return;

            String cropId = ChatColor.stripColor(clicked.getItemMeta().getLore().get(0));
            SAOFarmManager.CropData crop = plugin.getFarmManager().getCrop(cropId);

            if (crop != null && stats.col >= crop.price) {
                stats.col -= crop.price;
                ItemStack seed = new ItemStack(Material.MELON_SEEDS);
                ItemMeta meta = seed.getItemMeta();
                meta.setDisplayName("§b" + crop.name);
                meta.setLore(Arrays.asList("§8" + crop.id, "§7Category: §f農業", "", "§f" + crop.description));
                seed.setItemMeta(meta);
                player.getInventory().addItem(seed);

                plugin.getPlayerManager().savePlayerData(player, stats);
                if (plugin.getSaoScoreboard() != null) plugin.getSaoScoreboard().updateScoreboard(player, stats);
                player.sendMessage("§a" + crop.name + " §aを購入しました！");
                player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
            } else if (crop != null) {
                player.sendMessage("§b[System] §r§cINSUFFICIENT COL");
            }
        }
    }

    /**
     * farmId (座標文字列) から対応するアーマースタンドを探し、メニューを再表示する
     * リロード後も座標で正しく紐付けられる
     */
    private void reopenFarmMenu(Player player, String farmId) {
        // farmId = "worldName:x:y:z"
        String[] parts = farmId.split(":");
        int len = parts.length;
        if (len < 4) return;
        try {
            int z = Integer.parseInt(parts[len - 1]);
            int y = Integer.parseInt(parts[len - 2]);
            int x = Integer.parseInt(parts[len - 3]);
            String worldName = String.join(":", java.util.Arrays.copyOfRange(parts, 0, len - 3));
            org.bukkit.World world = Bukkit.getWorld(worldName);
            if (world == null) return;

            org.bukkit.Location center = new org.bukkit.Location(world, x, y, z);
            for (org.bukkit.entity.Entity e : world.getNearbyEntities(center, 1, 1, 1)) {
                if (e instanceof org.bukkit.entity.ArmorStand && e.getScoreboardTags().contains("sao_farm_point")) {
                    openFarmMenu(player, e);
                    return;
                }
            }
        } catch (NumberFormatException e) {
            e.printStackTrace();
        }
    }

    private ItemStack createItem(Material m, String name, List<String> lore) {
        ItemStack item = new ItemStack(m);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }
}