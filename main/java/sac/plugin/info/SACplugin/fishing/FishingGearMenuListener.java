package sac.plugin.info.SACplugin.fishing;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import sac.plugin.info.SACplugin.SACplugin;

import java.util.ArrayList;
import java.util.List;

/**
 * ★新規: 竿にリール・ルアーを取り付けるGUI。
 * 釣竿を持った状態でしゃがみ+右クリックすると開く(SAOFishingListener側から呼ばれる)。
 * - 上段: 現在の装備状況の表示・取り外しボタン
 * - 中段: 所持しているリールの一覧(クリックで取り付け)
 * - 下段: 所持しているルアーの一覧(クリックで取り付け)
 */
public class FishingGearMenuListener implements Listener {
    private final SACplugin plugin;
    private static final String GUI_TITLE = "§b《 リール・ルアー装備 》";

    // ★開いている間、対象の竿(メインハンドのアイテムそのもの)を覚えておく
    private final java.util.Map<java.util.UUID, ItemStack> targetRod = new java.util.HashMap<>();

    public FishingGearMenuListener(SACplugin plugin) {
        this.plugin = plugin;
    }

    public void openGearMenu(Player player, ItemStack rodItem, FishingRodData rodData) {
        targetRod.put(player.getUniqueId(), rodItem);

        Inventory inv = Bukkit.createInventory(null, 36, GUI_TITLE);

        FishingReelData currentReel = plugin.getFishingManager().getAttachedReel(rodItem);
        FishingLureData currentLure = plugin.getFishingManager().getAttachedLure(rodItem);

        // --- 現在の装備表示・取り外しボタン(上段) ---
        inv.setItem(2, infoItem(Material.NAME_TAG, "§b現在のリール",
                currentReel != null ? currentReel.name : "§8(未装備)"));
        inv.setItem(6, infoItem(Material.NAME_TAG, "§b現在のルアー",
                currentLure != null ? currentLure.name : "§8(未装備)"));
        inv.setItem(3, actionItem(Material.BARRIER, "§cリールを外す"));
        inv.setItem(5, actionItem(Material.BARRIER, "§cルアーを外す"));

        // --- 所持しているリール(中段: 9〜17) ---
        int slot = 9;
        for (ItemStack invItem : player.getInventory().getContents()) {
            if (slot > 17) break;
            String id = extractId(invItem);
            if (id == null) continue;
            FishingReelData reel = plugin.getFishingManager().getReel(id);
            if (reel == null) continue;
            inv.setItem(slot, plugin.getFishingManager().createReelItem(reel));
            slot++;
        }

        // --- 所持しているルアー(下段: 18〜26) ---
        slot = 18;
        for (ItemStack invItem : player.getInventory().getContents()) {
            if (slot > 26) break;
            String id = extractId(invItem);
            if (id == null) continue;
            FishingLureData lure = plugin.getFishingManager().getLure(id);
            if (lure == null) continue;
            inv.setItem(slot, plugin.getFishingManager().createLureItem(lure));
            slot++;
        }

        for (int i = 27; i < 36; i++) inv.setItem(i, background());

        player.openInventory(inv);
        player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
    }

    private String extractId(ItemStack item) {
        if (item == null || !item.hasItemMeta() || !item.getItemMeta().hasLore()) return null;
        List<String> lore = item.getItemMeta().getLore();
        if (lore == null || lore.isEmpty()) return null;
        return ChatColor.stripColor(lore.get(0));
    }

    private ItemStack infoItem(Material mat, String name, String value) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(java.util.Arrays.asList("§f" + value));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack actionItem(Material mat, String name) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack background() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(" ");
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!event.getView().getTitle().equals(GUI_TITLE)) return;
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;

        Player player = (Player) event.getWhoClicked();
        ItemStack rodItem = targetRod.get(player.getUniqueId());
        if (rodItem == null) return;

        String rodId = extractId(rodItem);
        FishingRodData rodData = rodId != null ? plugin.getFishingManager().getRod(rodId) : null;
        if (rodData == null) return;

        int slot = event.getRawSlot();

        if (slot == 3) {
            // リールを外す
            ItemMeta meta = rodItem.getItemMeta();
            meta.getPersistentDataContainer().remove(plugin.getFishingManager().rodReelIdKey);
            rodItem.setItemMeta(meta);
            plugin.getFishingManager().refreshRodLore(rodItem, rodData);
            player.sendMessage("§b[System] §r§7リールを外しました。");
            player.closeInventory();
            return;
        }
        if (slot == 5) {
            // ルアーを外す
            ItemMeta meta = rodItem.getItemMeta();
            meta.getPersistentDataContainer().remove(plugin.getFishingManager().rodLureIdKey);
            rodItem.setItemMeta(meta);
            plugin.getFishingManager().refreshRodLore(rodItem, rodData);
            player.sendMessage("§b[System] §r§7ルアーを外しました。");
            player.closeInventory();
            return;
        }

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta() || !clicked.getItemMeta().hasLore()) return;
        String clickedId = ChatColor.stripColor(clicked.getItemMeta().getLore().get(0));

        if (slot >= 9 && slot <= 17) {
            FishingReelData reel = plugin.getFishingManager().getReel(clickedId);
            if (reel != null) {
                plugin.getFishingManager().attachReel(rodItem, rodData, clickedId);
                player.sendMessage("§b[System] §r§a「" + reel.name + "§a」を取り付けました！");
                player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                player.closeInventory();
            }
        } else if (slot >= 18 && slot <= 26) {
            FishingLureData lure = plugin.getFishingManager().getLure(clickedId);
            if (lure != null) {
                plugin.getFishingManager().attachLure(rodItem, rodData, clickedId);
                player.sendMessage("§b[System] §r§a「" + lure.name + "§a」を取り付けました！");
                player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                player.closeInventory();
            }
        }
    }
}
