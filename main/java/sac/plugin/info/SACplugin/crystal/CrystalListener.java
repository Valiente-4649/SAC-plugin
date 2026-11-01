package sac.plugin.info.SACplugin.crystal;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import sac.plugin.info.SACplugin.Player.PlayerData;
import sac.plugin.info.SACplugin.SACplugin;
import sac.plugin.info.SACplugin.item.SAOGeneralItemData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class CrystalListener implements Listener {
    private final SACplugin plugin;

    public CrystalListener(SACplugin plugin) {
        this.plugin = plugin;
    }

    // ==========================================
    // 転移結晶の右クリック使用
    // ==========================================
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;

        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta() || !item.getItemMeta().hasLore()) return;

        ItemMeta meta = item.getItemMeta();
        String idStr = ChatColor.stripColor(meta.getLore().get(0));

        SAOGeneralItemData itemData = plugin.getSaoItemManager().getGeneralItem(idStr);
        if (itemData == null || !"結晶".equals(itemData.category)) return;

        event.setCancelled(true);

        if (idStr.startsWith("tp_")) {
            // ==========================================
            // ★追加: チュートリアル中は転移結晶による転移を禁止する
            // (座標指定によるteleport自体は許可のまま)
            // ==========================================
            if (sac.plugin.info.SACplugin.Tutorial.TutorialManager.activeTutorial.containsKey(player.getUniqueId())) {
                player.sendMessage("§b[System] §r§cチュートリアル中は転移結晶を使用できません。");
                return;
            }
            handleTpCrystal(player, item, meta);
        } else if (idStr.startsWith("heal_")) {
            handleHealCrystal(player, item, meta, itemData);
        }
    }

    // ==========================================
    // 転移結晶の処理
    // ==========================================
    private void handleTpCrystal(Player player, ItemStack item, ItemMeta meta) {
        if (player.isSneaking()) {
            openTpGroupMenu(player);
        } else {
            // ==========================================
            // ★追加: 転移結晶のクールタイム判定(1個使うと5秒間、次を使えないようにする)
            // ==========================================
            if (plugin.getCrystalManager().isTpCrystalCooldown(player.getUniqueId())) {
                player.sendMessage("§b[System] §r§c転移結晶はクールタイム中です！(5秒)");
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                return;
            }

            int targetFloor = getRegisteredFloor(meta);

            if (targetFloor == -1) {
                player.sendMessage("§b[System] §r§c転移先が設定されていません。スニーク＋右クリックで設定してください。");
                return;
            }

            // ★ OP含むすべての条件チェックをCrystalManagerに委譲
            if (!plugin.getCrystalManager().canTeleport(player, targetFloor)) {
                player.sendMessage(plugin.getCrystalManager().getTeleportBlockReason(player, targetFloor));
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                return;
            }

            org.bukkit.Location targetLoc = plugin.getCrystalManager().getTpLocation(targetFloor);
            if (targetLoc == null) {
                player.sendMessage("§b[System] §r§c転移先のデータが存在しません。");
                return;
            }

            consumeItem(player, item);
            // ★追加: 使用した瞬間からクールタイムを開始する
            plugin.getCrystalManager().startTpCrystalCooldown(player.getUniqueId());
            executeTeleport(player, targetLoc);
            player.sendMessage("§b[System] §r§b転移結晶を使用しました");
        }
    }

    private void handleHealCrystal(Player player, ItemStack item, ItemMeta meta, SAOGeneralItemData itemData) {
        PlayerData stats = plugin.getPlayerManager().getStats(player);
        if (stats == null) return;

        consumeItem(player, item);

        // ★実装: HPを即座に全回復する
        stats.currentHP = stats.maxHP;
        plugin.getPlayerManager().applyVisualHealth(player, stats);
        plugin.getPlayerManager().savePlayerData(player, stats);
        if (plugin.getSaoScoreboard() != null) {
            plugin.getSaoScoreboard().updateScoreboard(player, stats);
        }

        player.sendMessage("§b[System] §r§a回復結晶を使用しました！ §7(HP全回復)");
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.5f);
    }

    // ==========================================
    // ★ 転移門 (TextDisplay) のクリック
    // Interaction エンティティ経由でクリックを検知
    // ==========================================
    @EventHandler
    public void onTeleportGateClick(PlayerInteractAtEntityEvent event) {
        if (!(event.getRightClicked() instanceof org.bukkit.entity.Interaction)) return;
        org.bukkit.entity.Interaction interaction = (org.bukkit.entity.Interaction) event.getRightClicked();
        if (!interaction.getScoreboardTags().contains("sao_teleport_gate")) return;

        event.setCancelled(true);
        Player player = event.getPlayer();

        // 転移結晶のGUIと同じ層選択メニューを開く
        openTpGroupMenu(player);
        player.sendMessage("§b[転移門] 転移先を選択してください。");
        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.5f);
    }

    // ==========================================
    // テレポート共通処理
    // ==========================================
    private void consumeItem(Player player, ItemStack item) {
        if (item.getAmount() > 1) item.setAmount(item.getAmount() - 1);
        else player.getInventory().setItemInMainHand(null);
    }

    private void executeTeleport(Player player, org.bukkit.Location dest) {
        player.getWorld().spawnParticle(org.bukkit.Particle.PORTAL, player.getLocation(), 100, 0.5, 1.0, 0.5, 0.5);
        player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
        player.teleport(dest);
        player.getWorld().spawnParticle(org.bukkit.Particle.PORTAL, dest, 100, 0.5, 1.0, 0.5, 0.5);
        player.playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
    }

    // ==========================================
    // GUI: グループ選択 → 層選択
    // ==========================================
    public void openTpGroupMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 9, "§b転移結晶 - グループ選択");
        inv.setItem(1, createButton(Material.GRASS_BLOCK,    "§a下層",   "§7第1層 ～ 第27層"));
        inv.setItem(3, createButton(Material.STONE,          "§e中層",   "§7第28層 ～ 第54層"));
        inv.setItem(5, createButton(Material.IRON_BLOCK,     "§c上層",   "§7第55層 ～ 第81層"));
        inv.setItem(7, createButton(Material.DIAMOND_BLOCK,  "§b最上層", "§7第82層 ～ 第100層"));

        // ★ スロット4（中央）にオート緊急離脱ボタン
        boolean autoOn = plugin.getCrystalManager().isAutoEscapeEnabled(player.getUniqueId());
        if (autoOn) {
            inv.setItem(4, createButton(Material.LIME_DYE, "§aオート緊急離脱: ON",
                    "§7HP が §c20% §7を切ると自動で転移結晶を使用します",
                    "", "§eクリックでOFFにする"));
        } else {
            inv.setItem(4, createButton(Material.GRAY_DYE, "§7オート緊急離脱: OFF",
                    "§7HP が §c20% §7を切ると自動で転移結晶を使用します",
                    "", "§eクリックでONにする"));
        }

        player.openInventory(inv);
    }

    public void openTpFloorMenu(Player player, String groupName, int startFloor, int endFloor) {
        Inventory inv = Bukkit.createInventory(null, 36, "§b転移先選択: " + groupName);
        int slot = 0;
        for (int i = startFloor; i <= endFloor; i++) {
            if (slot >= 36) break;
            boolean canTp = plugin.getCrystalManager().canTeleport(player, i);
            String floorName = plugin.getCrystalManager().getFloorName(i);
            boolean hasData = !floorName.equals("未解放");

            if (canTp) {
                // 転移可能
                inv.setItem(slot, createButton(Material.LIGHT_BLUE_STAINED_GLASS_PANE,
                        "§a第" + i + "層", "§f" + floorName, "", "§eクリックして転移先設定"));
            } else if (hasData && !plugin.getCrystalManager().isFloorUnlocked(player, i) && player.isOp()) {
                // OPで解放条件は満たしていないがOPスキップ可 → 黄色で表示
                inv.setItem(slot, createButton(Material.YELLOW_STAINED_GLASS_PANE,
                        "§e第" + i + "層", "§f" + floorName, "§7[OP] ボス未討伐だが転移可能"));
            } else {
                // 未解放
                inv.setItem(slot, createButton(Material.GRAY_STAINED_GLASS_PANE,
                        "§7第" + i + "層", "§c未解放"));
            }
            slot++;
        }
        player.openInventory(inv);
    }

    private ItemStack createButton(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(Arrays.asList(lore));
        item.setItemMeta(meta);
        return item;
    }

    private int getRegisteredFloor(ItemMeta meta) {
        if (meta.hasLore()) {
            for (String line : meta.getLore()) {
                String plain = ChatColor.stripColor(line);
                if (plain.startsWith("転移先: 第") && plain.contains("層")) {
                    try {
                        String numStr = plain.substring(plain.indexOf("第") + 1, plain.indexOf("層"));
                        return Integer.parseInt(numStr);
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        return -1;
    }

    // ==========================================
    // インベントリクリック (層選択・転移先設定)
    // ==========================================
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();
        if (!title.startsWith("§b転移")) return;
        event.setCancelled(true);

        Player player = (Player) event.getWhoClicked();
        ItemStack currentItem = event.getCurrentItem();
        if (currentItem == null || !currentItem.hasItemMeta()) return;

        String itemName = currentItem.getItemMeta().getDisplayName();

        if (title.equals("§b転移結晶 - グループ選択")) {
            // ★ スロット4はオート緊急離脱ボタン
            if (event.getSlot() == 4) {
                boolean current = plugin.getCrystalManager().isAutoEscapeEnabled(player.getUniqueId());
                plugin.getCrystalManager().setAutoEscape(player.getUniqueId(), !current);
                if (!current) {
                    player.sendMessage("§a[EMERGENCY TELEPORT] オートEMERGENCY TELEPORTを ON §aにしました！HP20%以下で自動転移します。");
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 2f);
                } else {
                    player.sendMessage("§7[EMERGENCY TELEPORT] オートEMERGENCY TELEPORTを OFF §7にしました。");
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
                }
                // メニューを更新
                openTpGroupMenu(player);
                return;
            }

            if (itemName.equals("§a下層"))   openTpFloorMenu(player, "下層",   1,  27);
            else if (itemName.equals("§e中層")) openTpFloorMenu(player, "中層", 28, 54);
            else if (itemName.equals("§c上層")) openTpFloorMenu(player, "上層", 55, 81);
            else if (itemName.equals("§b最上層")) openTpFloorMenu(player, "最上層", 82, 100);
            return;
        }

        if (title.startsWith("§b転移先選択: ")) {
            // 未解放スロットをクリック
            if (itemName.startsWith("§7")) {
                player.sendMessage("§b[System] §r§cその階層はまだ解放されていません！");
                return;
            }

            // 転移可能スロット (§a または §e=OPスキップ) をクリック
            String colorStripped = ChatColor.stripColor(itemName);
            if (colorStripped.startsWith("第") && colorStripped.contains("層")) {
                try {
                    String numStr = colorStripped.replace("第", "").replace("層", "").trim();
                    int floor = Integer.parseInt(numStr);

                    // ★ 再度 canTeleport チェック (GUIを直接操作された場合の保険)
                    if (!plugin.getCrystalManager().canTeleport(player, floor)) {
                        player.sendMessage(plugin.getCrystalManager().getTeleportBlockReason(player, floor));
                        return;
                    }

                    // 転移結晶から開いた場合 → 転移先を設定
                    // 転移門から開いた場合 → 即テレポート
                    ItemStack handItem = player.getInventory().getItemInMainHand();
                    boolean hasCrystal = handItem != null && handItem.hasItemMeta()
                            && handItem.getItemMeta().hasLore()
                            && ChatColor.stripColor(handItem.getItemMeta().getLore().get(0)).startsWith("tp_");

                    if (hasCrystal) {
                        // 転移結晶の転移先を設定
                        String floorName = ChatColor.stripColor(currentItem.getItemMeta().getLore().get(0));
                        ItemMeta meta = handItem.getItemMeta();
                        List<String> lore = meta.hasLore() ? meta.getLore() : new ArrayList<>();
                        lore.removeIf(line -> ChatColor.stripColor(line).startsWith("転移先:"));
                        lore.add("§e転移先: 第" + floor + "層 (§f" + floorName + "§e)");
                        meta.setLore(lore);
                        handItem.setItemMeta(meta);
                        player.sendMessage("§b[System] §r§a転移結晶の転移先を「第" + floor + "層」に設定しました");
                        player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.0f, 1.0f);
                    } else {
                        // 転移門から開いた → 即テレポート
                        org.bukkit.Location dest = plugin.getCrystalManager().getTpLocation(floor);
                        if (dest == null) { player.sendMessage("§b[System] §r§c転移先データがありません。"); return; }
                        executeTeleport(player, dest);
                        player.sendMessage("§b[転移門] 第" + floor + "層へ転移しました");
                    }
                    player.closeInventory();
                } catch (Exception ignored) {}
            }
        }
    }

    // ==========================================
    // ★ オート緊急離脱: ダメージ後にHP20%以下なら自動でTP
    // MONITOR優先度で、ダメージ反映後の実際のHPを見る
    // ==========================================
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageAutoEscape(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();

        // オート緊急離脱がOFFなら無視
        if (!plugin.getCrystalManager().isAutoEscapeEnabled(player.getUniqueId())) return;

        // クールダウン中は無視
        if (plugin.getCrystalManager().isAutoEscapeCooldown(player.getUniqueId())) return;

        // SAO独自HP(currentHP)で20%チェック
        PlayerData stats = plugin.getPlayerManager().getStats(player);
        if (stats == null) return;
        double hpAfter = stats.currentHP - event.getFinalDamage();
        if (hpAfter / stats.maxHP > 0.2) return;

        // 転移先が設定された転移結晶をインベントリから探す
        ItemStack crystal = null;
        int crystalSlot = -1;

        // ★修正: オフハンドの結晶が見つからないバグを修正。
        // メインインベントリのループとは別に、オフハンドを明示的にチェックする。
        ItemStack offHand = player.getInventory().getItemInOffHand();
        boolean offHandIsRegisteredCrystal = false;
        if (offHand != null && offHand.hasItemMeta() && offHand.getItemMeta().hasLore()) {
            String offHandId = ChatColor.stripColor(offHand.getItemMeta().getLore().get(0));
            if (offHandId.startsWith("tp_")) {
                int offHandFloor = getRegisteredFloor(offHand.getItemMeta());
                if (offHandFloor != -1 && plugin.getCrystalManager().canTeleport(player, offHandFloor)) {
                    crystal = offHand;
                    crystalSlot = -1; // オフハンドはスロット番号を使わず専用処理で消費する
                    offHandIsRegisteredCrystal = true;
                }
            }
        }

        if (!offHandIsRegisteredCrystal) {
            for (int i = 0; i < player.getInventory().getSize(); i++) {
                ItemStack it = player.getInventory().getItem(i);
                if (it == null || !it.hasItemMeta() || !it.getItemMeta().hasLore()) continue;
                String id = ChatColor.stripColor(it.getItemMeta().getLore().get(0));
                if (!id.startsWith("tp_")) continue;
                int floor = getRegisteredFloor(it.getItemMeta());
                if (floor != -1 && plugin.getCrystalManager().canTeleport(player, floor)) {
                    crystal = it;
                    crystalSlot = i;
                    break;
                }
            }
        }

        if (crystal == null) {
            player.sendMessage("§c[EMERGENCY TELEPORT] 転移先が設定された転移結晶が見つかりません");
            return;
        }

        final ItemStack finalCrystal = crystal;
        final int finalSlot = crystalSlot;

        // クールダウン開始（連続発動防止）
        plugin.getCrystalManager().startAutoEscapeCooldown(player.getUniqueId());

        // 1tick後に実行（ダメージイベント完了後）
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;

            int floor = getRegisteredFloor(finalCrystal.getItemMeta());
            if (floor == -1 || !plugin.getCrystalManager().canTeleport(player, floor)) {
                player.sendMessage("§c[EMERGENCY TELEPORT] 転移できませんでした。");
                return;
            }

            org.bukkit.Location dest = plugin.getCrystalManager().getTpLocation(floor);
            if (dest == null) return;

            // アイテム消費
            // ★修正: オフハンドの結晶(finalSlot == -1)は setItemInOffHand で消費する
            if (finalCrystal.getAmount() > 1) {
                finalCrystal.setAmount(finalCrystal.getAmount() - 1);
            } else if (finalSlot == -1) {
                player.getInventory().setItemInOffHand(null);
            } else {
                player.getInventory().setItem(finalSlot, null);
            }

            // 演出
            player.sendTitle("§cEMERGENCY TELEPORT", "§f転移結晶が自動で使用されました", 5, 30, 10);
            player.sendMessage("§c[EMERGENCY TELEPORT] §fHP20%以下を検知！転移結晶を自動使用しました。");
            executeTeleport(player, dest);
        }, 1L);
    }
}