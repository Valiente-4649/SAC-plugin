package sac.plugin.info.SACplugin.fishing;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import sac.plugin.info.SACplugin.SACplugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SAOFishingListener implements Listener {
    private final SACplugin plugin;
    public static final Map<UUID, FishingSession> activeSessions = new HashMap<>();

    public SAOFishingListener(SACplugin plugin) { this.plugin = plugin; }

    @EventHandler
    public void onFishInteract(PlayerInteractEvent event) {

        // ==========================================
        // ★最重要追加: オフハンドによる2重判定(ダブルクリック)を完全に防ぐ！
        // ==========================================
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;

        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();

        if (item == null || !item.hasItemMeta() || !item.getItemMeta().hasLore()) return;
        String itemId = org.bukkit.ChatColor.stripColor(item.getItemMeta().getLore().get(0));
        FishingRodData rod = plugin.getFishingManager().getRod(itemId);
        if (rod == null) return;

        // リールを巻く動作、またはキャンセル (釣り進行中)
        if (activeSessions.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
            activeSessions.get(player.getUniqueId()).onReel();
            return;
        }

        // ==========================================
        // ★追加: しゃがみ+右クリックでリール・ルアーの取り付けメニューを開く
        // ==========================================
        if (player.isSneaking() && (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK)) {
            event.setCancelled(true);
            plugin.getFishingGearMenuListener().openGearMenu(player, item, rod);
            return;
        }

        // 釣り開始の動作 (右クリックの時だけ発動)
        if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {

            sac.plugin.info.SACplugin.Player.PlayerData stats = plugin.getPlayerManager().getStats(player);
            boolean hasSkill = false;
            if (stats != null) {
                for (String s : stats.equippedSkills) {
                    if ("釣り".equals(s)) { hasSkill = true; break; }
                }
            }
            if (!hasSkill) {
                player.sendMessage("§b[System] §r§cスキルスロットに「釣り」をセットしていないため、釣竿を使用できません！");
                player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                return;
            }

            int prof = stats != null ? stats.proficiency.getOrDefault("Fishing", 1) : 1;
            if (prof < rod.requiredProficiency) {
                player.sendMessage("§b[System] §r§c釣りの熟練度が足りません！(必要Lv: " + rod.requiredProficiency + ")");
                player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                return;
            }

            // ==========================================
            // ★追加: 竿の耐久値チェック。0になっていたら使用不可(修理不可・買い直し)
            // ==========================================
            if (plugin.getFishingManager().getRodDurability(item, rod) <= 0) {
                player.sendMessage("§b[System] §r§cこの竿は壊れています！新しい竿を購入してください。");
                player.playSound(player.getLocation(), org.bukkit.Sound.ITEM_SHIELD_BREAK, 1.0f, 1.0f);
                return;
            }

            org.bukkit.block.Block targetBlock = player.getTargetBlockExact(15, org.bukkit.FluidCollisionMode.ALWAYS);

            if (targetBlock != null && targetBlock.getType() == org.bukkit.Material.WATER) {
                // ==========================================
                // ★変更: 釣りスポットの有無だけでなく、そのランクも取得する
                // ==========================================
                String spotRank = getNearbyFishingPointRank(player);
                if (spotRank != null) {
                    event.setCancelled(true);

                    // ★追加: 釣りを試みるたびに耐久値を1消費する(成功・失敗問わず)
                    plugin.getFishingManager().consumeRodDurability(item, rod);

                    org.bukkit.Location bobberLoc = targetBlock.getLocation().add(0.5, 0.9, 0.5);
                    startFishing(player, rod, item, bobberLoc, spotRank);
                } else {
                    player.sendMessage("§b[System] §r§cここでは魚の気配がない... (Fishing Pointの近くで釣りをしてください)");
                }
            }
        }
    }

    /** 近くのFishing Pointのランク("C"/"B"/"A"/"S")を返す。無ければnull */
    private String getNearbyFishingPointRank(Player p) {
        for (org.bukkit.entity.Entity e : p.getNearbyEntities(10, 10, 10)) {
            for (String tag : e.getScoreboardTags()) {
                if (tag.startsWith("Fishing Point")) {
                    String[] parts = tag.split(" ");
                    if (parts.length >= 3) {
                        return parts[2].toUpperCase(); // "Fishing Point A" -> "A"
                    }
                    return "C"; // ランク指定の無い古いタグは最低ランク扱いにする
                }
            }
        }
        return null;
    }

    // ★変更: 引数に item(竿そのもの)・spotRank(スポットのランク)を追加
    private void startFishing(Player p, FishingRodData rod, ItemStack rodItem, org.bukkit.Location bobberLoc, String spotRank) {
        sac.plugin.info.SACplugin.fishing.FishingReelData reel = plugin.getFishingManager().getAttachedReel(rodItem);
        sac.plugin.info.SACplugin.fishing.FishingLureData lure = plugin.getFishingManager().getAttachedLure(rodItem);
        FishingSession session = new FishingSession(plugin, p, rod, rodItem, bobberLoc, reel, lure, spotRank);
        activeSessions.put(p.getUniqueId(), session);
        p.sendMessage("§a[Fishing] §f釣糸を垂らしました... (魚が掛かるのを待っています)");
        p.sendMessage("§b[System] §r§7※クリックでキャンセルできます"); // ★案内を追加
    }

    @EventHandler
    public void onItemHeldChange(org.bukkit.event.player.PlayerItemHeldEvent event) {
        if (activeSessions.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§c[!] 釣りを中断しました");
            activeSessions.get(event.getPlayer().getUniqueId()).cancel();
            activeSessions.remove(event.getPlayer().getUniqueId());
        }
    }

    // ==========================================
    // ★新規追加: 釣り中の移動ロック (視点移動は可能！)
    // ==========================================
    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (activeSessions.containsKey(event.getPlayer().getUniqueId())) {
            org.bukkit.Location from = event.getFrom();
            org.bukkit.Location to = event.getTo();

            // X, Y, Z のどれかが動いた場合(歩く、ジャンプする等)だけ引き戻す
            if (to != null && (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ())) {
                org.bukkit.Location newLoc = from.clone();
                newLoc.setYaw(to.getYaw());     // 視点の横回転は許可
                newLoc.setPitch(to.getPitch()); // 視点の縦回転は許可
                event.setTo(newLoc);
            }
        }
    }
    @EventHandler
    public void onFishingNpcInteract(org.bukkit.event.player.PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof org.bukkit.entity.Villager)) return;
        org.bukkit.entity.Villager npc = (org.bukkit.entity.Villager) event.getRightClicked();

        if (npc.getScoreboardTags().contains("sao_fishing_npc")) {
            event.setCancelled(true);
            Player player = event.getPlayer();

            // ★変更: リール・ルアーの購入ボタンを追加するため9→18スロットに拡張
            org.bukkit.inventory.Inventory inv = org.bukkit.Bukkit.createInventory(null, 18, "§b《 釣りおじさん 》");

            // 釣竿ショップボタン
            ItemStack shop = new ItemStack(org.bukkit.Material.FISHING_ROD);
            org.bukkit.inventory.meta.ItemMeta shopMeta = shop.getItemMeta();
            shopMeta.setDisplayName("§a釣竿を買う");
            shopMeta.setLore(java.util.Arrays.asList("§7様々なランクの釣竿を購入できます"));
            shop.setItemMeta(shopMeta);
            inv.setItem(2, shop);

            // ★追加: リールショップボタン
            ItemStack reelShop = new ItemStack(org.bukkit.Material.STRING);
            org.bukkit.inventory.meta.ItemMeta reelMeta = reelShop.getItemMeta();
            reelMeta.setDisplayName("§bリールを買う");
            reelMeta.setLore(java.util.Arrays.asList("§7竿に取り付けるリールを購入できます"));
            reelShop.setItemMeta(reelMeta);
            inv.setItem(4, reelShop);

            // ★追加: ルアーショップボタン
            ItemStack lureShop = new ItemStack(org.bukkit.Material.FEATHER);
            org.bukkit.inventory.meta.ItemMeta lureMeta = lureShop.getItemMeta();
            lureMeta.setDisplayName("§6ルアーを買う");
            lureMeta.setLore(java.util.Arrays.asList("§7竿に取り付けるルアーを購入できます"));
            lureShop.setItemMeta(lureMeta);
            inv.setItem(6, lureShop);

            // 魚図鑑ボタン
            ItemStack book = new ItemStack(org.bukkit.Material.BOOK);
            org.bukkit.inventory.meta.ItemMeta bookMeta = book.getItemMeta();
            bookMeta.setDisplayName("§d魚図鑑を見る");
            bookMeta.setLore(java.util.Arrays.asList("§7これまでに釣り上げた魚のコレクションを確認します"));
            book.setItemMeta(bookMeta);
            inv.setItem(8, book);

            player.openInventory(inv);
            player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
        }
    }

    @EventHandler
    public void onInventoryClick(org.bukkit.event.inventory.InventoryClickEvent event) {
        String title = event.getView().getTitle();
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();

        if (title.equals("§b《 釣りおじさん 》")) {
            event.setCancelled(true);
            if (event.getRawSlot() == 2) {
                player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                openRodShop(player);
            } else if (event.getRawSlot() == 4) {
                player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                openReelShop(player);
            } else if (event.getRawSlot() == 6) {
                player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                openLureShop(player);
            } else if (event.getRawSlot() == 8) {
                player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                openFishEncyclopedia(player);
            }
        }
        else if (title.equals("§b《 釣竿ショップ 》")) {
            event.setCancelled(true);
            if (event.getRawSlot() == 45) {
                player.closeInventory();
                return;
            }

            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || !clicked.hasItemMeta() || !clicked.getItemMeta().hasLore()) return;

            String rodId = org.bukkit.ChatColor.stripColor(clicked.getItemMeta().getLore().get(0));
            FishingRodData rod = plugin.getFishingManager().getRod(rodId);

            if (rod != null) {
                sac.plugin.info.SACplugin.Player.PlayerData stats = plugin.getPlayerManager().getStats(player);
                if (stats.col >= rod.price) {
                    stats.col -= rod.price;
                    player.getInventory().addItem(plugin.getFishingManager().createRodItem(rod));
                    plugin.getPlayerManager().savePlayerData(player, stats);
                    if (plugin.getSaoScoreboard() != null) plugin.getSaoScoreboard().updateScoreboard(player, stats);

                    player.sendMessage("§a[System] 「" + rod.name + "」を購入しました！");
                    player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                } else {
                    player.sendMessage("§b[System] §r§cINSUFFICIENT COL (価格: " + rod.price + " Col)");
                    player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                }
            }
        }
        // ★追加: リールショップの購入処理
        else if (title.equals("§b《 リールショップ 》")) {
            event.setCancelled(true);
            if (event.getRawSlot() == 45) {
                player.closeInventory();
                return;
            }

            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || !clicked.hasItemMeta() || !clicked.getItemMeta().hasLore()) return;

            String reelId = org.bukkit.ChatColor.stripColor(clicked.getItemMeta().getLore().get(0));
            sac.plugin.info.SACplugin.fishing.FishingReelData reel = plugin.getFishingManager().getReel(reelId);

            if (reel != null) {
                sac.plugin.info.SACplugin.Player.PlayerData stats = plugin.getPlayerManager().getStats(player);
                if (stats.col >= reel.price) {
                    stats.col -= reel.price;
                    player.getInventory().addItem(plugin.getFishingManager().createReelItem(reel));
                    plugin.getPlayerManager().savePlayerData(player, stats);
                    if (plugin.getSaoScoreboard() != null) plugin.getSaoScoreboard().updateScoreboard(player, stats);

                    player.sendMessage("§a[System] 「" + reel.name + "」を購入しました！");
                    player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                } else {
                    player.sendMessage("§b[System] §r§cINSUFFICIENT COL (価格: " + reel.price + " Col)");
                    player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                }
            }
        }
        // ★追加: ルアーショップの購入処理
        else if (title.equals("§b《 ルアーショップ 》")) {
            event.setCancelled(true);
            if (event.getRawSlot() == 45) {
                player.closeInventory();
                return;
            }

            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || !clicked.hasItemMeta() || !clicked.getItemMeta().hasLore()) return;

            String lureId = org.bukkit.ChatColor.stripColor(clicked.getItemMeta().getLore().get(0));
            sac.plugin.info.SACplugin.fishing.FishingLureData lure = plugin.getFishingManager().getLure(lureId);

            if (lure != null) {
                sac.plugin.info.SACplugin.Player.PlayerData stats = plugin.getPlayerManager().getStats(player);
                if (stats.col >= lure.price) {
                    stats.col -= lure.price;
                    player.getInventory().addItem(plugin.getFishingManager().createLureItem(lure));
                    plugin.getPlayerManager().savePlayerData(player, stats);
                    if (plugin.getSaoScoreboard() != null) plugin.getSaoScoreboard().updateScoreboard(player, stats);

                    player.sendMessage("§a[System] 「" + lure.name + "」を購入しました！");
                    player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                } else {
                    player.sendMessage("§b[System] §r§cINSUFFICIENT COL (価格: " + lure.price + " Col)");
                    player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                }
            }
        }
        else if (title.equals("§b《 魚図鑑 》")) {
            event.setCancelled(true);
            if (event.getRawSlot() == 45) player.closeInventory();
        }
    }

    // ★追加: リールショップを開く
    private void openReelShop(Player player) {
        org.bukkit.inventory.Inventory inv = org.bukkit.Bukkit.createInventory(null, 54, "§b《 リールショップ 》");
        java.util.List<sac.plugin.info.SACplugin.fishing.FishingReelData> reelList =
                new java.util.ArrayList<>(plugin.getFishingManager().getAllReels().values());
        reelList.sort(java.util.Comparator.comparingInt(r -> rankOrder(r.rank)));
        for (sac.plugin.info.SACplugin.fishing.FishingReelData reel : reelList) {
            ItemStack item = plugin.getFishingManager().createReelItem(reel);
            org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
            java.util.List<String> lore = meta.getLore();
            lore.add("");
            lore.add("§e▶ 価格: §6" + reel.price + " Col");
            meta.setLore(lore);
            item.setItemMeta(meta);
            inv.addItem(item);
        }

        ItemStack back = new ItemStack(org.bukkit.Material.BARRIER);
        org.bukkit.inventory.meta.ItemMeta backMeta = back.getItemMeta();
        backMeta.setDisplayName("§c閉じる");
        back.setItemMeta(backMeta);
        inv.setItem(45, back);

        player.openInventory(inv);
    }

    // ★追加: ルアーショップを開く
    private void openLureShop(Player player) {
        org.bukkit.inventory.Inventory inv = org.bukkit.Bukkit.createInventory(null, 54, "§b《 ルアーショップ 》");
        java.util.List<sac.plugin.info.SACplugin.fishing.FishingLureData> lureList =
                new java.util.ArrayList<>(plugin.getFishingManager().getAllLures().values());
        lureList.sort(java.util.Comparator.comparingInt(l -> rankOrder(l.rank)));
        for (sac.plugin.info.SACplugin.fishing.FishingLureData lure : lureList) {
            ItemStack item = plugin.getFishingManager().createLureItem(lure);
            org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
            java.util.List<String> lore = meta.getLore();
            lore.add("");
            lore.add("§e▶ 価格: §6" + lure.price + " Col");
            meta.setLore(lore);
            item.setItemMeta(meta);
            inv.addItem(item);
        }

        ItemStack back = new ItemStack(org.bukkit.Material.BARRIER);
        org.bukkit.inventory.meta.ItemMeta backMeta = back.getItemMeta();
        backMeta.setDisplayName("§c閉じる");
        back.setItemMeta(backMeta);
        inv.setItem(45, back);

        player.openInventory(inv);
    }

    private void openRodShop(Player player) {
        org.bukkit.inventory.Inventory inv = org.bukkit.Bukkit.createInventory(null, 54, "§b《 釣竿ショップ 》");
        java.util.List<FishingRodData> rodList = new java.util.ArrayList<>(plugin.getFishingManager().getAllRods().values());
        rodList.sort(java.util.Comparator.comparingInt(r -> rankOrder(r.rank)));
        for (FishingRodData rod : rodList) {
            ItemStack item = plugin.getFishingManager().createRodItem(rod);
            org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
            java.util.List<String> lore = meta.getLore();
            lore.add("");
            lore.add("§e▶ 価格: §6" + rod.price + " Col");
            meta.setLore(lore);
            item.setItemMeta(meta);
            inv.addItem(item);
        }

        ItemStack back = new ItemStack(org.bukkit.Material.BARRIER);
        org.bukkit.inventory.meta.ItemMeta backMeta = back.getItemMeta();
        backMeta.setDisplayName("§c閉じる");
        back.setItemMeta(backMeta);
        inv.setItem(45, back);

        player.openInventory(inv);
    }

    /** ★追加: ランク文字列(C/B/A/S)を並び替え用の数値に変換する共通ヘルパー */
    private int rankOrder(String rank) {
        if (rank == null) return 99;
        switch (rank.toUpperCase()) {
            case "C": return 0;
            case "B": return 1;
            case "A": return 2;
            case "S": return 3;
            default: return 99;
        }
    }

    private void openFishEncyclopedia(Player player) {
        org.bukkit.inventory.Inventory inv = org.bukkit.Bukkit.createInventory(null, 54, "§b《 魚図鑑 》");
        sac.plugin.info.SACplugin.Player.PlayerData stats = plugin.getPlayerManager().getStats(player);

        // ★追加: もしリストがnullだったら初期化する
        if (stats != null && stats.caughtFishes == null) {
            stats.caughtFishes = new java.util.ArrayList<>();
        }

        for (FishData fish : plugin.getFishingManager().getAllFishes().values()) {
            if (stats != null && stats.caughtFishes.contains(fish.id)) {
                // 釣ったことがある魚は詳細を表示
                ItemStack item = plugin.getFishingManager().createFishItem(fish);
                inv.addItem(item);
            } else {
                // 釣ったことがない魚はシルエット（黒いガラス）にする
                ItemStack unknown = new ItemStack(org.bukkit.Material.BLACK_STAINED_GLASS_PANE);
                org.bukkit.inventory.meta.ItemMeta unknownMeta = unknown.getItemMeta();
                unknownMeta.setDisplayName("§8??? (未発見)");
                unknownMeta.setLore(java.util.Arrays.asList("§7Rank: §f" + fish.rank));
                unknown.setItemMeta(unknownMeta);
                inv.addItem(unknown);
            }
        }

        ItemStack back = new ItemStack(org.bukkit.Material.BARRIER);
        org.bukkit.inventory.meta.ItemMeta backMeta = back.getItemMeta();
        backMeta.setDisplayName("§c閉じる");
        back.setItemMeta(backMeta);
        inv.setItem(45, back);

        player.openInventory(inv);
    }

    // ==========================================
    // ★追加: 釣りスポット設置コマンド(ランク指定)。/fishingspot <C/B/A/S>
    // ==========================================
    public static class SpotSpawnCommand implements org.bukkit.command.CommandExecutor {
        @Override
        public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
            if (!(sender instanceof Player)) return true;
            Player player = (Player) sender;
            if (!player.isOp()) {
                player.sendMessage("§b[System] §r§cPERMISSION DENIED");
                return true;
            }
            if (args.length < 1 || !java.util.Arrays.asList("C", "B", "A", "S").contains(args[0].toUpperCase())) {
                player.sendMessage("§b[System] §r§c使用法: /fishingspot <C/B/A/S>");
                return true;
            }
            String rank = args[0].toUpperCase();

            org.bukkit.entity.ArmorStand spot = player.getWorld().spawn(player.getLocation(), org.bukkit.entity.ArmorStand.class);
            spot.setVisible(false);
            spot.setGravity(false);
            spot.setInvulnerable(true);
            spot.setCustomName("§bFishing Point [" + rank + "]");
            spot.setCustomNameVisible(true);
            spot.addScoreboardTag("Fishing Point " + rank);

            player.sendMessage("§a[System] 釣りスポット(ランク" + rank + ")を設置しました！");
            return true;
        }
    }
}