package sac.plugin.info.SACplugin.Event;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import sac.plugin.info.SACplugin.Player.PlayerData;
import sac.plugin.info.SACplugin.SACplugin;
import sac.plugin.info.SACplugin.item.SAOArmor;
import sac.plugin.info.SACplugin.item.SAOGeneralItemData;
import sac.plugin.info.SACplugin.item.SAOWeapon;

import java.util.ArrayList;
import java.util.List;

public class SAOSellListener implements Listener {
    private final SACplugin plugin;
    private final String GUI_TITLE = "§bアイテム買取屋";

    public SAOSellListener(SACplugin plugin) { this.plugin = plugin; }

    // 買取屋を開く
    public void openSellGUI(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, GUI_TITLE);

        // 右下（26番スロット）に売却確定ボタンを設置
        ItemStack confirmBtn = new ItemStack(Material.GREEN_STAINED_GLASS_PANE);
        ItemMeta meta = confirmBtn.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§a売却を確定する");
            List<String> lore = new ArrayList<>();
            lore.add("§f中に入れたアイテムをすべて売却し、");
            lore.add("§f購入額の半額の Col を受け取ります。");
            lore.add("§c※耐久値が減っている装備は売れません");
            meta.setLore(lore);
            confirmBtn.setItemMeta(meta);
        }
        inv.setItem(26, confirmBtn);

        player.openInventory(inv);
        player.playSound(player.getLocation(), Sound.BLOCK_WOODEN_DOOR_OPEN, 1.0f, 1.2f);
    }

    // ==========================================
    // ★ 買取価格の計算処理 (修正版: 一般アイテムと装備で計算を分ける)
    // ==========================================
    private long calculatePrice(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta() || !item.getItemMeta().hasLore()) return 0;

        // ==========================================
        // ★追加: アクセサリー(加工済み)は、耐久値の概念が無いためaccessory.jsonの
        // 固定買取価格でそのまま売却できる。
        // ==========================================
        if (plugin.getAccessoryManager() != null && plugin.getAccessoryManager().isAccessory(item)) {
            return plugin.getAccessoryManager().getSellPrice() * item.getAmount();
        }

        // 鑑定素材もアクセサリーと同じ買取価格で売れる
        if (plugin.getAccessoryManager() != null && plugin.getAccessoryManager().isAppraisedMaterial(item)) {
            return plugin.getAccessoryManager().getSellPrice() * item.getAmount();
        }

        String idLine = ChatColor.stripColor(item.getItemMeta().getLore().get(0));

        // ==========================================
        // ★追加: 魚は個体ごとにサイズが異なり価格も変動するため、
        // 釣り上げた瞬間にSAOFishingManager.createFishItem()がPDCへ保存した
        // 最終価格(fish_sell_price)をそのまま使う。
        // ==========================================
        if (plugin.getFishingManager() != null && plugin.getFishingManager().getFish(idLine) != null) {
            org.bukkit.NamespacedKey fishPriceKey = new org.bukkit.NamespacedKey(plugin, "fish_sell_price");
            Long storedPrice = item.getItemMeta().getPersistentDataContainer()
                    .get(fishPriceKey, org.bukkit.persistence.PersistentDataType.LONG);
            if (storedPrice == null || storedPrice <= 0) return 0;
            return storedPrice * item.getAmount();
        }

        long finalSellPrice = 0;
        int maxDur = -1;
        int currentDur = -1;

        SAOWeapon weapon = plugin.getSaoItemManager().getWeapon(idLine);
        SAOArmor armor = plugin.getSaoItemManager().getArmor(idLine);
        SAOGeneralItemData genItem = plugin.getSaoItemManager().getGeneralItem(idLine);

        if (weapon != null) {
            finalSellPrice = weapon.price / 2; // 武器は購入額の半額
            maxDur = weapon.maxDurability;
        } else if (armor != null) {
            finalSellPrice = armor.price / 2;  // 防具は購入額の半額
            maxDur = armor.maxDurability;
        } else if (genItem != null) {
            // ==========================================
            // ★修正: 一般アイテムは json に設定された「sellPrice」をそのまま使う！
            // ==========================================
            finalSellPrice = genItem.sellPrice;
            if (finalSellPrice <= 0) return 0; // 買取価格が0のアイテムは売れない
        } else {
            return 0; // システム外のアイテムは売れない
        }

        // 耐久値の読み取り (武器・防具のみ)
        if (maxDur > 0) {
            for (String line : item.getItemMeta().getLore()) {
                String plain = ChatColor.stripColor(line);
                if (plain.startsWith("Durability: ")) {
                    String[] parts = plain.replace("Durability: ", "").split(" / ");
                    if (parts.length == 2) {
                        try {
                            currentDur = Integer.parseInt(parts[0].trim());
                        } catch (Exception ignored) {}
                    }
                    break;
                }
            }

            // ★ 耐久値が1でも減っていたら売却不可(エラー値: -1)を返す！
            if (currentDur >= 0 && currentDur < maxDur) {
                return -1;
            }
        }

        // アイテムの個数を掛けて返す
        return finalSellPrice * item.getAmount();
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getView().getTitle().equals(GUI_TITLE)) return;
        if (event.getCurrentItem() == null) return;

        // 緑のガラス(26番)をクリックした時だけ処理
        if (event.getRawSlot() == 26) {
            event.setCancelled(true);
            sellItems((Player) event.getWhoClicked(), event.getInventory());
        }
    }

    private void sellItems(Player player, Inventory inv) {
        long totalCol = 0;
        boolean hasDamagedItem = false;

        // 26番スロット以外の中身をすべて確認
        for (int i = 0; i < 26; i++) {
            ItemStack item = inv.getItem(i);
            long price = calculatePrice(item);

            if (price > 0) {
                totalCol += price;
                inv.setItem(i, null); // 売却したアイテムを消去
            } else if (price == -1) {
                // 耐久値が減っているアイテムを発見した
                hasDamagedItem = true;
            }
        }

        // 結果のフィードバック処理
        if (totalCol > 0) {
            PlayerData stats = plugin.getPlayerManager().getStats(player);
            stats.col += totalCol;
            plugin.getPlayerManager().savePlayerData(player, stats);
            if (plugin.getSaoScoreboard() != null) {
                plugin.getSaoScoreboard().updateScoreboard(player, stats);
            }

            player.sendMessage("§e[買取屋] §fアイテムを売却し、 §e" + totalCol + " Col §fを獲得しました！");
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.2f);

            if (hasDamagedItem) {
                player.sendMessage("§b[System] §r§c※耐久値が減っている一部の装備は買い取れませんでした。");
            }
        } else if (hasDamagedItem) {
            player.sendMessage("§b[System] §r§c耐久値が減っている（使用済み）の装備は買い取れません！");
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
        } else {
            player.sendMessage("§b[System] §r§c売却できるアイテムが入っていません。");
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
        }
    }

    // ==========================================
    // ★追加: メニューを閉じた時に売れ残ったアイテムを返す
    // ==========================================
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!event.getView().getTitle().equals(GUI_TITLE)) return;

        Player player = (Player) event.getPlayer();
        Inventory inv = event.getInventory();

        for (int i = 0; i < 26; i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && item.getType() != Material.AIR) {
                if (!player.getInventory().addItem(item).isEmpty()) {
                    player.getWorld().dropItem(player.getLocation(), item);
                }
            }
        }
    }
}