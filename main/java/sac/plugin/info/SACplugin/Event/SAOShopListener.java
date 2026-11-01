package sac.plugin.info.SACplugin.Event;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import sac.plugin.info.SACplugin.Player.PlayerData;
import sac.plugin.info.SACplugin.SACplugin;
import sac.plugin.info.SACplugin.item.SAOGeneralItemData;
import sac.plugin.info.SACplugin.item.SAOShopData;
import sac.plugin.info.SACplugin.item.SAOWeapon;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SAOShopListener implements Listener {
    private final SACplugin plugin;

    public SAOShopListener(SACplugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onVillagerInteract(PlayerInteractEntityEvent event) {
        if (plugin.getVitalityManager() != null && plugin.getVitalityManager().isNpcClosed(event.getRightClicked())) return;
        if (!(event.getRightClicked() instanceof Villager)) return;
        Villager villager = (Villager) event.getRightClicked();
        Player player = event.getPlayer();

        // 村人が持っている「タグ」をすべて調べる
        for (String tag : villager.getScoreboardTags()) {

            // 1. 買取屋（売却専用）の判定
            if (tag.equals("sao_shop_sell")) {
                event.setCancelled(true);
                // 後ほど作成する SAOSellListener の GUI を開く
                if (plugin.getSaoSellListener() != null) {
                    plugin.getSaoSellListener().openSellGUI(player);
                }
                return;
            }

            // 2. 通常ショップ（購入用）の判定
            if (tag.startsWith("sao_shop_")) {
                event.setCancelled(true);
                String shopId = tag.replace("sao_shop_", "");
                openShopGUI(player, shopId);
                return;
            }
        }
    }

    private void openShopGUI(Player player, String shopId) {
        SAOShopData shopData = plugin.getSaoItemManager().getShop(shopId);
        if (shopData == null) return;

        Inventory inv = Bukkit.createInventory(null, 27, "§bSAC Shop" + shopData.name);

        for (String itemId : shopData.items) {
            ItemStack displayItem = null;
            long price = 0;

            // ショップタイプに応じて取得先を切り替える
            if ("weapon".equalsIgnoreCase(shopData.type)) {
                SAOWeapon weapon = plugin.getSaoItemManager().getWeapon(itemId);
                if (weapon != null) {
                    displayItem = plugin.getSaoItemManager().createWeaponItem(weapon);
                    price = weapon.price;
                }
            } else if ("armor".equalsIgnoreCase(shopData.type)) { // ★追加: 防具ショップ
                sac.plugin.info.SACplugin.item.SAOArmor armor = plugin.getSaoItemManager().getArmor(itemId);
                if (armor != null) {
                    displayItem = plugin.getSaoItemManager().createArmorItem(armor);
                    price = armor.price;
                }
            } else if ("general".equalsIgnoreCase(shopData.type)) {
                SAOGeneralItemData generalItem = plugin.getSaoItemManager().getGeneralItem(itemId);
                if (generalItem != null) {
                    displayItem = plugin.getSaoItemManager().createGeneralItem(generalItem);
                    price = generalItem.price;
                }
            }

            if (displayItem != null) {
                ItemMeta meta = displayItem.getItemMeta();
                if (meta != null) {
                    List<String> lore = meta.hasLore() ? meta.getLore() : new ArrayList<>();
                    lore.add(" ");
                    lore.add("§e価格: " + price + " col");
                    lore.add("§7▶ クリックで購入");
                    meta.setLore(lore);
                    displayItem.setItemMeta(meta);
                }
                inv.addItem(displayItem);
            }
        }
        player.openInventory(inv);
        player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getView().getTitle().startsWith("§bSAC Shop")) return;

        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        Player player = (Player) event.getWhoClicked();
        ItemStack clicked = event.getCurrentItem();

        // meta.hasLore() を使って、Loreが存在するか安全に確認する
        if (clicked == null || !clicked.hasItemMeta() || !clicked.getItemMeta().hasLore()) {
            return;
        }

        // ここまで来れば、lore.get(0) を安全に呼び出せます
        String itemId = org.bukkit.ChatColor.stripColor(clicked.getItemMeta().getLore().get(0));

        // ショップデータを特定（タイトルから名前を照合するなど工夫が必要ですが、ここではロジックを優先）
        // アイテムが武器か一般アイテムかを判定して処理
        SAOWeapon weapon = plugin.getSaoItemManager().getWeapon(itemId);
        sac.plugin.info.SACplugin.item.SAOArmor armor = plugin.getSaoItemManager().getArmor(itemId);
        SAOGeneralItemData general = plugin.getSaoItemManager().getGeneralItem(itemId);

        long price = 0;
        ItemStack finalItem = null;

        if (weapon != null) {
            price = weapon.price;
            finalItem = plugin.getSaoItemManager().createWeaponItem(weapon);
        } else if (armor != null) { // ★追加
            price = armor.price;
            finalItem = plugin.getSaoItemManager().createArmorItem(armor);
        } else if (general != null) {
            price = general.price;
            finalItem = plugin.getSaoItemManager().createGeneralItem(general);
        }

        if (finalItem != null) {
            PlayerData stats = plugin.getPlayerManager().getStats(player);
            if (stats.col >= price) {
                stats.col -= price;
                player.getInventory().addItem(finalItem);

                plugin.getPlayerManager().savePlayerData(player, stats);
                plugin.getSaoScoreboard().updateScoreboard(player, stats);
                player.sendMessage("§a[System] 購入しました");
                player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
            } else {
                player.sendMessage("§b[System] §r§cINSUFFICIENT COL。");
            }
        }
    }
}