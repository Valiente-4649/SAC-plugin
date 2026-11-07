package sac.plugin.info.SACplugin.shop;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import sac.plugin.info.SACplugin.Player.PlayerData;
import sac.plugin.info.SACplugin.SACplugin;

import java.util.*;

public class PlayerShopListener implements Listener {
    private final SACplugin plugin;

    // 価格設定待ちのデータ (店主のUUID -> 出品データ)
    private final Map<UUID, PendingListing> pendingListings = new HashMap<>();
    // 購入確認画面を開いているプレイヤー (購入者のUUID -> 確認データ)
    private final Map<UUID, ConfirmData> confirmSessions = new HashMap<>();

    public PlayerShopListener(SACplugin plugin) { this.plugin = plugin; }

    private static class PendingListing {
        UUID shopId; int slot; ItemStack item;
        public PendingListing(UUID shopId, int slot, ItemStack item) { this.shopId = shopId; this.slot = slot; this.item = item; }
    }

    private static class ConfirmData {
        UUID shopId; int slot; PlayerShopManager.ShopItem shopItem;
        public ConfirmData(UUID shopId, int slot, PlayerShopManager.ShopItem shopItem) { this.shopId = shopId; this.slot = slot; this.shopItem = shopItem; }
    }

    // 1. ショップの Interaction エンティティをクリックした時
    @EventHandler
    public void onShopClick(PlayerInteractAtEntityEvent event) {
        if (!(event.getRightClicked() instanceof org.bukkit.entity.Interaction)) return;
        org.bukkit.entity.Interaction interaction = (org.bukkit.entity.Interaction) event.getRightClicked();

        if (interaction.getScoreboardTags().contains("sao_player_shop")) {
            event.setCancelled(true);
            Player player = event.getPlayer();
            PlayerShopManager.PlayerShop shop = plugin.getPlayerShopManager().getShop(interaction.getUniqueId());
            if (shop == null) return;

            openShopMenu(player, shop, interaction.getUniqueId());

            if (shop.ownerUuid.equals(player.getUniqueId())) {
                player.sendMessage("§a[System] 自分のショップを開きました。§e上段の商品をクリックで回収、下段から出品できます。");
            } else {
                player.sendMessage("§e[System] いらっしゃいませ！");
            }
            player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 1f, 1f);
        }
    }

    /** ショップメニューを開く共通メソッド */
    private void openShopMenu(Player player, PlayerShopManager.PlayerShop shop, java.util.UUID shopId) {
        Inventory inv = Bukkit.createInventory(null, 27, "§b《 " + shop.shopName + " 》");
        for (Map.Entry<Integer, PlayerShopManager.ShopItem> entry : shop.items.entrySet()) {
            ItemStack displayItem = entry.getValue().item.clone();
            ItemMeta meta = displayItem.getItemMeta();
            List<String> lore = meta.hasLore() ? meta.getLore() : new ArrayList<>();
            lore.add("");
            if (shop.ownerUuid.equals(player.getUniqueId())) {
                lore.add("§c▶ クリックで回収");
            } else {
                lore.add("§e▶ 販売価格: §6" + entry.getValue().price + " Col");
            }
            meta.setLore(lore);
            displayItem.setItemMeta(meta);
            inv.setItem(entry.getKey(), displayItem);
        }
        player.openInventory(inv);
    }

    // 2. ショップインベントリのクリック処理
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        String title = event.getView().getTitle();

        if (title.startsWith("§b《 ")) {
            if (title.contains("Storage")) return;
            event.setCancelled(true); // 基本は移動禁止

            // ★ Interaction エンティティからショップを特定する
            org.bukkit.entity.Interaction shopInteraction = null;
            PlayerShopManager.PlayerShop shop = null;
            for (org.bukkit.entity.Entity e : player.getNearbyEntities(5, 5, 5)) {
                if (e instanceof org.bukkit.entity.Interaction && e.getScoreboardTags().contains("sao_player_shop")) {
                    shop = plugin.getPlayerShopManager().getShop(e.getUniqueId());
                    if (shop != null && ("§b《 " + shop.shopName + " 》").equals(title)) {
                        shopInteraction = (org.bukkit.entity.Interaction) e;
                        break;
                    }
                }
            }
            if (shop == null) return;

            // ラムダで使うためにfinalコピーを作成
            final PlayerShopManager.PlayerShop finalShopRef = shop;
            final UUID finalShopIdRef = shop.shopId;

            boolean isOwner = shop.ownerUuid.equals(player.getUniqueId());

            if (isOwner && event.getClickedInventory() != null && event.getClickedInventory().equals(event.getView().getTopInventory())) {
                // ★ 店主が商品棚(上側)をクリック → 商品を回収する
                ItemStack clicked = event.getCurrentItem();
                if (clicked == null || clicked.getType() == Material.AIR) return;

                PlayerShopManager.ShopItem shopItem = shop.items.get(event.getSlot());
                if (shopItem == null) return;

                // インベントリに返却（溢れたら足元にドロップ）
                java.util.HashMap<Integer, ItemStack> leftovers = player.getInventory().addItem(shopItem.item.clone());
                for (ItemStack leftover : leftovers.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                }

                // 棚から削除して保存
                shop.items.remove(event.getSlot());
                plugin.getPlayerShopManager().saveShops();

                player.sendMessage("§a[System] 商品を回収しました。");
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 1.2f);

                // メニューを更新（再度開き直す）
                player.closeInventory();
                org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    openShopMenu(player, finalShopRef, finalShopIdRef);
                }, 1L);
                return;
            }

            if (isOwner && event.getClickedInventory() != null && event.getClickedInventory().equals(event.getView().getBottomInventory())) {
                // 店主が自分の手持ち(下側)をクリックして出品しようとした
                ItemStack clicked = event.getCurrentItem();
                if (clicked == null || clicked.getType() == Material.AIR || !clicked.hasItemMeta()) return;

                // ==========================================
                // ★追加: 商業スキルの熟練度による出品枠の制限チェック
                // ==========================================
                PlayerData stats = plugin.getPlayerManager().getStats(player);
                int currentProf = stats.proficiency.getOrDefault("Commerce", 1);
                int maxSlots = currentProf + 2; // Lv1=3枠, Lv2=4枠...

                if (shop.items.size() >= maxSlots) {
                    player.sendMessage("§b[System] §r§c商品棚の空き枠がありません！");
                    player.sendMessage("§b[System] §r§c(現在の商業Lv: " + currentProf + " / 出品上限: " + maxSlots + " 枠)");
                    player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                    return;
                }

                int targetSlot = invFirstEmpty(event.getView().getTopInventory());
                if (targetSlot == -1) {
                    player.sendMessage("§b[System] §r§c商品棚がいっぱいです！");
                    return;
                }

                // 武器は1個固定、その他は最大20個の制限
                ItemStack toSell = clicked.clone();
                String idLine = ChatColor.stripColor(clicked.getItemMeta().getLore().get(0));
                if (plugin.getSaoItemManager().getWeapon(idLine) != null) {
                    toSell.setAmount(1);
                } else {
                    toSell.setAmount(Math.min(clicked.getAmount(), 20));
                }

                // 手持ちから減らす
                clicked.setAmount(clicked.getAmount() - toSell.getAmount());

                // 出品待ち状態にしてチャットを待機
                pendingListings.put(player.getUniqueId(), new PendingListing(shop.shopId, targetSlot, toSell));
                player.closeInventory();
                player.sendMessage("§a[System] 出品するアイテムをセットしました。");
                player.sendMessage("§b[System] §r§e▶ チャットで【販売価格】を数字で入力してください！(キャンセルは cancel と入力)");
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 2f);
                return;
            }

            if (!isOwner && event.getClickedInventory() != null && event.getClickedInventory().equals(event.getView().getTopInventory())) {
                // 客が商品棚(上側)をクリックして買おうとした
                ItemStack clicked = event.getCurrentItem();
                if (clicked == null || clicked.getType() == Material.AIR) return;

                PlayerShopManager.ShopItem shopItem = shop.items.get(event.getSlot());
                if (shopItem == null) return;

                confirmSessions.put(player.getUniqueId(), new ConfirmData(shop.shopId, event.getSlot(), shopItem));
                openConfirmGUI(player, shopItem);
            }
        }
        // 3. 購入確認画面の処理
        else if (title.equals("§c《 購入確認 》")) {
            event.setCancelled(true);
            ConfirmData data = confirmSessions.get(player.getUniqueId());
            if (data == null) return;

            if (event.getRawSlot() == 11) { // 購入ボタン
                PlayerData stats = plugin.getPlayerManager().getStats(player);
                if (stats.col >= data.shopItem.price) {
                    // 購入処理
                    stats.col -= data.shopItem.price;
                    player.getInventory().addItem(data.shopItem.item.clone());
                    plugin.getPlayerManager().savePlayerData(player, stats);
                    plugin.getSaoScoreboard().updateScoreboard(player, stats);

                    // 店主の売上に追加
                    PlayerShopManager.PlayerShop shop = plugin.getPlayerShopManager().getShop(data.shopId);

                    // 商品棚から削除 (売り切れ) ★在庫削除を先に行う
                    shop.items.remove(data.slot);

                    Player owner = Bukkit.getPlayer(shop.ownerUuid);
                    if (owner != null && owner.isOnline()) {
                        // ★店主がオンラインなら即座に売上とEXPを付与
                        PlayerData ownerStats = plugin.getPlayerManager().getStats(owner);
                        ownerStats.col += data.shopItem.price;
                        owner.sendMessage("§e[System] あなたのショップで商品が売れました！ (+" + data.shopItem.price + " Col)");
                        owner.playSound(owner.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);

                        addCommerceExp(owner, ownerStats, data.shopItem.price);

                        plugin.getPlayerManager().savePlayerData(owner, ownerStats);
                        plugin.getSaoScoreboard().updateScoreboard(owner, ownerStats);
                        plugin.getPlayerShopManager().saveShops(); // 在庫削除も含めて保存
                    } else {
                        // ★オフラインならマネージャーに預ける（saveShopsもここで呼ばれる）
                        plugin.getPlayerShopManager().addPendingSale(shop.ownerUuid, data.shopItem.price);
                        plugin.getPlayerShopManager().saveShops(); // 在庫削除を確実に保存
                    }

                    // ★追加(実績): 店主の売上(商業スキルの累計売上)を記録する。店主がオフラインでも記録される。
                    if (plugin.getAchievementManager() != null) plugin.getAchievementManager().onShopSale(shop.ownerUuid, data.shopItem.price);

                    player.sendMessage("§a[System] 商品を購入しました！ (-" + data.shopItem.price + " Col)");
                    player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.5f);
                    player.closeInventory();
                    confirmSessions.remove(player.getUniqueId());
                } else {
                    player.sendMessage("§b[System] §r§cINSUFFICIENT COL");
                    player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                }
            } else if (event.getRawSlot() == 15) { // キャンセルボタン
                player.closeInventory();
                confirmSessions.remove(player.getUniqueId());
            }
        }
    }

    // 4. チャットでの値段入力処理
    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        PendingListing pending = pendingListings.get(player.getUniqueId());

        if (pending != null) {
            event.setCancelled(true);
            String msg = event.getMessage();

            if (msg.equalsIgnoreCase("cancel")) {
                player.getInventory().addItem(pending.item); // アイテムを返す
                pendingListings.remove(player.getUniqueId());
                player.sendMessage("§b[System] §r§c出品をキャンセルし、アイテムを返却しました。");
                return;
            }

            try {
                long price = Long.parseLong(msg);
                if (price < 0) throw new NumberFormatException();

                PlayerShopManager.PlayerShop shop = plugin.getPlayerShopManager().getShop(pending.shopId);
                if (shop != null) {
                    shop.items.put(pending.slot, new PlayerShopManager.ShopItem(pending.item, price));
                    player.sendMessage("§a[System] 価格を §e" + price + " Col §aに設定し、棚に並べました！");
                    player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_YES, 1f, 1f);

                    // ★追加: 出品完了時にデータをファイルへ書き込む
                    plugin.getPlayerShopManager().saveShops();
                }
                pendingListings.remove(player.getUniqueId());
            } catch (NumberFormatException e) {
                player.sendMessage("§b[System] §r§c無効な数値です。正しい金額を入力するか、'cancel'と入力してください。");
            }
        }
    }

    // 5. ログイン時の売上金受け取り処理
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        // ★ 少し遅らせてPlayerDataが確実に読み込まれてから処理する
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player player = event.getPlayer();
            Long pendingCol = plugin.getPlayerShopManager().pendingSales.remove(player.getUniqueId());
            Long pendingExp = plugin.getPlayerShopManager().pendingExp.remove(player.getUniqueId());

            PlayerData stats = plugin.getPlayerManager().getStats(player);
            if (stats == null) return;

            boolean hasAny = false;

            // Col の付与
            if (pendingCol != null && pendingCol > 0) {
                stats.col += pendingCol;
                player.sendMessage("§6==============================");
                player.sendMessage("§e[System] §fオフライン中の売上金がありました！");
                player.sendMessage("§b[System] §r§a+" + pendingCol + " Col §fを獲得しました！");
                hasAny = true;
            }

            // 経験値の付与 (ColとEXPを独立して処理)
            if (pendingExp != null && pendingExp > 0) {
                addCommerceExp(player, stats, pendingExp);
                hasAny = true;
            }

            if (hasAny) {
                player.sendMessage("§6==============================");
                plugin.getPlayerManager().savePlayerData(player, stats);
                plugin.getPlayerShopManager().saveShops(); // pendingを削除した状態で保存
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
                org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    plugin.getSaoScoreboard().updateScoreboard(player, stats);
                }, 20L);
            }
        }, 40L); // 2秒後に実行
    }

    private void openConfirmGUI(Player player, PlayerShopManager.ShopItem shopItem) {
        Inventory inv = Bukkit.createInventory(null, 27, "§c《 購入確認 》");

        ItemStack info = shopItem.item.clone();
        ItemMeta meta = info.getItemMeta();
        List<String> lore = meta.hasLore() ? meta.getLore() : new ArrayList<>();
        lore.add("");
        lore.add("§e▶ 価格: §6" + shopItem.price + " Col");
        meta.setLore(lore);
        info.setItemMeta(meta);

        inv.setItem(13, info); // 中央に商品

        ItemStack buyBtn = new ItemStack(Material.GREEN_WOOL);
        ItemMeta buyMeta = buyBtn.getItemMeta();
        buyMeta.setDisplayName("§a購入する");
        buyBtn.setItemMeta(buyMeta);
        inv.setItem(11, buyBtn);

        ItemStack cancelBtn = new ItemStack(Material.RED_WOOL);
        ItemMeta cancelMeta = cancelBtn.getItemMeta();
        cancelMeta.setDisplayName("§cキャンセル");
        cancelBtn.setItemMeta(cancelMeta);
        inv.setItem(15, cancelBtn);

        player.openInventory(inv);
    }

    private int invFirstEmpty(Inventory inv) {
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null || inv.getItem(i).getType() == Material.AIR) return i;
        }
        return -1;
    }
    // ==========================================
    // ★追加: 商業スキルの経験値＆レベルアップ計算
    // ==========================================
    private void addCommerceExp(Player player, PlayerData stats, long expAdded) {
        int currentProf = stats.proficiency.getOrDefault("Commerce", 1);
        int limit = plugin.getLimitManager().getSkillLimit("Commerce"); // ★追加

        // ★追加: 既に上限なら何もしない
        if (currentProf >= limit) return;

        int currentExp = stats.proficiencyExp.getOrDefault("Commerce", 0) + (int) expAdded;
        boolean leveledUp = false;
        int requiredExp = currentProf * 5000;

        while (currentExp >= requiredExp) {
            // ★追加: 複数レベルアップして上限にぶつかったらストップ
            if (currentProf >= limit) {
                currentExp = 0;
                break;
            }
            currentExp -= requiredExp;
            currentProf++;
            requiredExp = currentProf * 5000;
            leveledUp = true;
        }

        stats.proficiencyExp.put("Commerce", currentExp);

        if (leveledUp) {
            stats.proficiency.put("Commerce", currentProf);
            player.sendMessage("§b[SKILL UP] §f商業スキルの熟練度が §e" + currentProf + " §fに上がりました！");
            player.sendMessage("§b[System] §r§aショップに並べられる商品の数が §e" + (currentProf + 2) + "枠 §aに増えました！");
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
        }
    }
}
