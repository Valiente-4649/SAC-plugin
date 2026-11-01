package sac.plugin.info.SACplugin.npc;

import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import sac.plugin.info.SACplugin.Player.PlayerData;
import sac.plugin.info.SACplugin.SACplugin;
import sac.plugin.info.SACplugin.item.SAOGeneralItemData;
import sac.plugin.info.SACplugin.item.SAOWeapon;
import sac.plugin.info.SACplugin.mob.SAOMobListener;

import java.util.HashMap;

public class SAOQuestListener implements Listener {
    private final SACplugin plugin;

    public SAOQuestListener(SACplugin plugin) {
        this.plugin = plugin;
    }

    // ==========================================
    // ★追加: クエストのrewardSkillUnlockを見て、体術などの武器種を解放する共通処理。
    // 既に解放済みなら何もしない。両方のクリア経路(即時クリア/報酬選択クリア)から呼ぶ。
    // ==========================================
    private void grantSkillUnlockIfAny(Player player, PlayerData stats, SAOQuestData quest) {
        if (quest.rewardSkillUnlock == null || quest.rewardSkillUnlock.isEmpty()) return;

        if (stats.unlockedWeaponTypes == null) stats.unlockedWeaponTypes = new java.util.ArrayList<>();
        if (stats.unlockedWeaponTypes.contains(quest.rewardSkillUnlock)) return;

        stats.unlockedWeaponTypes.add(quest.rewardSkillUnlock);
        player.sendMessage("§d[新規解放] §f「" + quest.rewardSkillUnlock + "」がスキルメニューで選べるようになりました！");
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.4f);
    }

    private String getTargetName(String targetId, String type) {
        if ("MOB".equals(type)) {
            sac.plugin.info.SACplugin.mob.SAOMobData mobData = plugin.getSaoMobManager().getMobData(targetId);
            if (mobData != null) return mobData.name;
        } else if ("ITEM".equals(type)) {
            SAOGeneralItemData itemData = plugin.getSaoItemManager().getGeneralItem(targetId);
            if (itemData != null && itemData.name != null) return itemData.name;

            SAOWeapon weaponData = plugin.getSaoItemManager().getWeapon(targetId);
            if (weaponData != null && weaponData.name != null) return weaponData.name;
        }
        return targetId; // 万が一見つからなければそのままIDを返す
    }

    @EventHandler
    public void onNpcInteract(PlayerInteractEntityEvent event) {
        if (plugin.getVitalityManager() != null && plugin.getVitalityManager().isNpcClosed(event.getRightClicked())) return;
        if (event.getRightClicked().getType() != EntityType.VILLAGER) return;

        Villager npc = (Villager) event.getRightClicked();

        for (String tag : npc.getScoreboardTags()) {
            if (tag.startsWith("sao_shop_")) return;
            if (tag.startsWith("DAILY_NPC_")) return; // ★追加: デイリーNPCは専用リスナー(DailyNPCListener)に処理を任せる
        }

        String npcName = npc.getCustomName();
        if (npcName == null) return;

        SAOQuestData quest = plugin.getQuestManager().getQuestByNpcName(npcName);

        if (quest != null) {
            event.setCancelled(true);

            Player player = event.getPlayer();
            processQuestInteraction(player, quest, npcName);
        }
    }

    // ==========================================
    // ★追加: 通常のNPC右クリックと、デイリーメニューの「今日のデイリー」クリックの
    // 両方から呼び出せるように、受注・進行・報告のロジックを共通化したメソッド。
    // ==========================================
    public void processQuestInteraction(Player player, SAOQuestData quest, String npcName) {
        PlayerData stats = plugin.getPlayerManager().getStats(player);
        if (stats == null) return;

        String targetName = getTargetName(quest.targetId, quest.type);

        // --- アイテム納品（ITEM）タイプの進行チェック ---
        if (quest.id.equals(stats.currentQuestId) && "ITEM".equals(quest.type) && !stats.questCompleted) {
            int count = 0;
            for (ItemStack item : player.getInventory().getContents()) {
                if (item == null || !item.hasItemMeta() || !item.getItemMeta().hasLore()) continue;

                String itemId = ChatColor.stripColor(item.getItemMeta().getLore().get(0));

                if (quest.targetId.equals(itemId)) {
                    int remaining = quest.requiredAmount - stats.questProgress;
                    int take = Math.min(item.getAmount(), remaining);

                    item.setAmount(item.getAmount() - take);
                    stats.questProgress += take;
                    count += take;

                    if (stats.questProgress >= quest.requiredAmount) break;
                }
            }

            if (count > 0) {
                player.sendMessage("§b[System] " + targetName + " を " + count + " 個納品しました。");
                if (stats.questProgress >= quest.requiredAmount) {
                    stats.questCompleted = true;
                    player.sendMessage("§b[System] §r§a必要数が集まりました！もう一度話しかけてください。");
                }
                plugin.getPlayerManager().savePlayerData(player, stats);
                return;
            }
        }

        // --- 報告・受注の分岐 ---
        if (stats.currentQuestId.equals(quest.id) && stats.questCompleted) {
            player.sendMessage("§7--- [ " + npcName + " §7] ---");
            for (String line : quest.completedDescriptions) {
                if (line != null && !line.isEmpty()) {
                    player.sendMessage(" " + line);
                }
            }

            // ==========================================
            // ★修正: 選択報酬がある場合はメニューを開いて、ここで一旦ストップする！
            // ==========================================
            if (quest.rewardChoices != null && !quest.rewardChoices.isEmpty()) {
                openRewardSelectionMenu(player, quest);
                return; // アイテムを選ぶまでColやEXPの付与はお預け
            }

            // ==========================================
            // 以下、選択報酬がないクエスト（今まで通りの即時クリア処理）
            // ==========================================
            stats.col += quest.rewardCol;
            stats.exp += quest.rewardExp;
            player.sendMessage("§e[System] §f" + quest.rewardCol + " Col / " + quest.rewardExp + " EXP");

            // ★追加: クエスト報酬による武器種(体術など)の解放
            grantSkillUnlockIfAny(player, stats, quest);

            if (quest.rewardItemId != null && !quest.rewardItemId.isEmpty()) {
                org.bukkit.inventory.ItemStack rewardItem = null;
                sac.plugin.info.SACplugin.item.SAOWeapon weapon = plugin.getSaoItemManager().getWeapon(quest.rewardItemId);
                if (weapon != null) {
                    rewardItem = plugin.getSaoItemManager().createWeaponItem(weapon);
                } else {
                    sac.plugin.info.SACplugin.item.SAOGeneralItemData genItem = plugin.getSaoItemManager().getGeneralItem(quest.rewardItemId);
                    if (genItem != null) {
                        rewardItem = plugin.getSaoItemManager().createGeneralItem(genItem);
                    }
                }

                if (rewardItem != null) {
                    rewardItem.setAmount(Math.max(1, quest.rewardItemAmount));
                    String displayName = rewardItem.hasItemMeta() && rewardItem.getItemMeta().hasDisplayName()
                            ? rewardItem.getItemMeta().getDisplayName() : quest.rewardItemId;

                    HashMap<Integer, org.bukkit.inventory.ItemStack> over = player.getInventory().addItem(rewardItem);
                    for (org.bukkit.inventory.ItemStack item : over.values()) {
                        player.getWorld().dropItemNaturally(player.getLocation(), item);
                    }
                    player.sendMessage("§b[System] §f" + displayName + " §ax" + rewardItem.getAmount() + " §fを受け取った！");
                }
            }

            // クリア回数を増加させる（デイリークエストは日付ベース管理のためカウント対象外）
            if (!quest.id.startsWith("daily_")) {
                if (stats.questCounts == null) stats.questCounts = new HashMap<>();
                int currentClearCount = stats.questCounts.getOrDefault(quest.id, 0);
                stats.questCounts.put(quest.id, currentClearCount + 1);
            } else {
                // ★変更: デイリークエストの完了日を記録する（全階層共通の1日1回制限にするため）
                try {
                    int floorNum = Integer.parseInt(quest.id.substring("daily_floor".length()));
                    String today = plugin.getDailyManager() != null
                            ? plugin.getDailyManager().getLogicalDateString()
                            : java.time.LocalDate.now().toString();
                    stats.lastDailyClearDate = today;

                    // ★追加: 抽選されたアイテム報酬(種類・個数)をまとめて付与する
                    plugin.getLogger().info("[Daily-Debug] quest completed: id=" + quest.id + " floorNum=" + floorNum
                            + " dailyManagerNull=" + (plugin.getDailyManager() == null));
                    if (plugin.getDailyManager() != null) {
                        sac.plugin.info.SACplugin.daily.DailyManager.DailyEntry dailyEntry =
                                plugin.getDailyManager().getEntry(floorNum);
                        plugin.getLogger().info("[Daily-Debug] dailyEntry=" + (dailyEntry == null ? "null"
                                : ("rewardItems.size=" + dailyEntry.rewardItems.size())));
                        if (dailyEntry != null) {
                            for (sac.plugin.info.SACplugin.daily.DailyManager.RewardStack rs : dailyEntry.rewardItems) {
                                plugin.getLogger().info("[Daily-Debug]   rewardItem itemId=" + rs.itemId + " amount=" + rs.amount);
                            }
                            plugin.getDailyManager().grantRewardItems(player, dailyEntry);
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().severe("[Daily-Debug] デイリー完了処理で例外が発生しました: " + e);
                    e.printStackTrace();
                }
            }

            // クエスト終了処理
            stats.currentQuestId = "";
            stats.questProgress = 0;
            stats.questCompleted = false;

            new SAOMobListener(plugin).processLevelUp(player, stats);

            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
            plugin.getSaoScoreboard().updateScoreboard(player, stats);
            plugin.getPlayerManager().savePlayerData(player, stats);
        }
        else if (stats.currentQuestId.equals(quest.id)) {
            player.sendMessage("§7--- [ " + npcName + " §7] ---");
            player.sendMessage("§b[System] §r§7まだ目標に届いていないようだ");
            player.sendMessage("§b 現在の進捗: " + stats.questProgress + " / " + quest.requiredAmount);
        }
        else if (stats.currentQuestId == null || stats.currentQuestId.isEmpty()) {
            if (stats.questCounts == null) stats.questCounts = new HashMap<>();
            int currentCount = stats.questCounts.getOrDefault(quest.id, 0);

            if (!quest.id.startsWith("daily_") && currentCount >= 3) {
                player.sendMessage("§b[System] このクエストは既に3回クリアしたため、もう受けることができません。");
                return;
            }

            player.sendMessage("§7--- [ " + npcName + " §7] ---");
            for (String line : quest.descriptions) {
                if (line != null && !line.isEmpty()) {
                    player.sendMessage(" " + line);
                }
            }

            player.sendMessage("");
            String goalType = quest.type.equals("MOB") ? "討伐" : "納品";
            player.sendMessage("§b[System] §r§bクエスト目標: " + targetName + " の" + goalType + " (" + quest.requiredAmount + ")");
            player.sendMessage("§7--------------------");

            stats.currentQuestId = quest.id;
            stats.questProgress = 0;
            stats.questCompleted = false;
            player.sendMessage("§b[System] §r§6クエスト [ " + quest.questName + " ] を受注しました");

            plugin.getPlayerManager().savePlayerData(player, stats);
        }
        else {
            // ==========================================
            // ★追加: 別のクエストを受注中の場合でも、新しいクエストが制限回数に達していないかチェック！
            // （デイリークエストは日付ベースの制限を使うため、この3回制限の対象外とする）
            // ==========================================
            if (stats.questCounts == null) stats.questCounts = new HashMap<>();
            int currentCount = stats.questCounts.getOrDefault(quest.id, 0);

            if (!quest.id.startsWith("daily_") && currentCount >= 3) {
                player.sendMessage("§b[System] このクエストは既に3回クリアしたため、もう受けることができません。");
                return; // 3回クリア済みなら、確認メニューすら開かせずに弾く！
            }

            // ★変更: 他のクエストを受けている場合、キャンセル確認GUIを開く
            openQuestCancelConfirmMenu(player, stats.currentQuestId, quest);
        }
    }

    // ==========================================
    // ★ クエストキャンセルの確認メニュー
    // ==========================================
    private void openQuestCancelConfirmMenu(Player player, String oldQuestId, SAOQuestData newQuest) {
        org.bukkit.inventory.Inventory inv = org.bukkit.Bukkit.createInventory(null, 9, "§b《Quest Cancel》");

        sac.plugin.info.SACplugin.npc.SAOQuestData oldQuest = plugin.getQuestManager().getQuest(oldQuestId);
        String oldName = oldQuest != null ? oldQuest.questName : "不明なクエスト";

        org.bukkit.inventory.ItemStack info = new org.bukkit.inventory.ItemStack(org.bukkit.Material.PAPER);
        org.bukkit.inventory.meta.ItemMeta infoMeta = info.getItemMeta();
        infoMeta.setDisplayName("§e他のクエストを受注中です");
        infoMeta.setLore(java.util.Arrays.asList(
                "§7現在進行中のクエスト: §c" + oldName,
                "§7これを破棄して、新しいクエストを受注しますか？",
                "§c※進行状況は0にリセットされます",
                "§c※クリア回数にはカウントされません"
        ));
        info.setItemMeta(infoMeta);
        inv.setItem(4, info);

        // 「はい」ボタンのLoreにこっそり新しいクエストIDを仕込んでおく
        org.bukkit.inventory.ItemStack yes = new org.bukkit.inventory.ItemStack(org.bukkit.Material.LIME_STAINED_GLASS_PANE);
        org.bukkit.inventory.meta.ItemMeta yesMeta = yes.getItemMeta();
        yesMeta.setDisplayName("§a[ はい ] 破棄して受注する");
        yesMeta.setLore(java.util.Arrays.asList("§8" + newQuest.id));
        yes.setItemMeta(yesMeta);
        inv.setItem(2, yes);

        org.bukkit.inventory.ItemStack no = new org.bukkit.inventory.ItemStack(org.bukkit.Material.RED_STAINED_GLASS_PANE);
        org.bukkit.inventory.meta.ItemMeta noMeta = no.getItemMeta();
        noMeta.setDisplayName("§c[ いいえ ] キャンセル");
        no.setItemMeta(noMeta);
        inv.setItem(6, no);

        player.openInventory(inv);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 0.8f);
    }

    @EventHandler
    public void onQuestCancelSelect(org.bukkit.event.inventory.InventoryClickEvent event) {
        if (!event.getView().getTitle().equals("§b《Quest Cancel》")) return;
        event.setCancelled(true);

        // ==========================================
        // ★追加: クリックした場所が「自分のインベントリ(下半分)」なら無視する！
        // ==========================================
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;

        Player player = (Player) event.getWhoClicked();
        org.bukkit.inventory.ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;

        String displayName = clicked.getItemMeta().getDisplayName();

        if (displayName.contains("[ いいえ ]")) {
            player.closeInventory();
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);

        } else if (displayName.contains("[ はい ]")) {
            String newQuestId = ChatColor.stripColor(clicked.getItemMeta().getLore().get(0));
            sac.plugin.info.SACplugin.npc.SAOQuestData newQuest = plugin.getQuestManager().getQuest(newQuestId);

            PlayerData stats = plugin.getPlayerManager().getStats(player);
            if (stats != null && newQuest != null) {
                // クエストを新しいものに上書きして進行度をリセット
                stats.currentQuestId = newQuest.id;
                stats.questProgress = 0;
                stats.questCompleted = false;

                player.sendMessage("§e[System] §c以前のクエストを破棄しました。");

                // ==========================================
                // ★追加: 新しいNPCのセリフと目標をしっかり表示する！
                // ==========================================
                String targetName = getTargetName(newQuest.targetId, newQuest.type);
                player.sendMessage("§7--- [ " + newQuest.npcName + " §7] ---");
                for (String line : newQuest.descriptions) {
                    if (line != null && !line.isEmpty()) {
                        player.sendMessage(" " + line);
                    }
                }
                player.sendMessage("");
                String goalType = newQuest.type.equals("MOB") ? "討伐" : "納品";
                player.sendMessage("§b[System] §r§bクエスト目標: " + targetName + " の" + goalType + " (" + newQuest.requiredAmount + ")");
                player.sendMessage("§7--------------------");
                // ==========================================

                player.sendMessage("§b[System] §r§6クエスト [ " + newQuest.questName + " ] を新たに受注しました");
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.5f);

                plugin.getPlayerManager().savePlayerData(player, stats);
            }
            player.closeInventory();
        }
    }

    // ==========================================
    // ★ 報酬選択メニューの処理
    // ==========================================
    private void openRewardSelectionMenu(Player player, SAOQuestData quest) {
        org.bukkit.inventory.Inventory inv = org.bukkit.Bukkit.createInventory(null, 9, "§b《Reward Selection》");

        for (String itemId : quest.rewardChoices) {
            org.bukkit.inventory.ItemStack displayItem = null;
            sac.plugin.info.SACplugin.item.SAOWeapon weapon = plugin.getSaoItemManager().getWeapon(itemId);

            if (weapon != null) {
                displayItem = plugin.getSaoItemManager().createWeaponItem(weapon);
            } else {
                sac.plugin.info.SACplugin.item.SAOGeneralItemData genItem = plugin.getSaoItemManager().getGeneralItem(itemId);
                if (genItem != null) {
                    displayItem = plugin.getSaoItemManager().createGeneralItem(genItem);
                }
            }

            if (displayItem != null) {
                org.bukkit.inventory.meta.ItemMeta meta = displayItem.getItemMeta();
                if (meta != null) {
                    java.util.List<String> lore = meta.hasLore() ? meta.getLore() : new java.util.ArrayList<>();
                    lore.add(" ");
                    lore.add("§a▶ クリックでこの報酬を受け取る");
                    meta.setLore(lore);
                    displayItem.setItemMeta(meta);
                }
                inv.addItem(displayItem);
            }
        }
        player.openInventory(inv);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 1.2f);
    }

    @EventHandler
    public void onRewardSelect(org.bukkit.event.inventory.InventoryClickEvent event) {
        if (!event.getView().getTitle().equals("§b《Reward Selection》")) return;
        event.setCancelled(true);

        // ==========================================
        // ★追加: 自分のインベントリをクリックした場合は無視する！
        // (自分の武器をクリックして増殖させるズルを完全に防ぎます)
        // ==========================================
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;

        Player player = (Player) event.getWhoClicked();
        org.bukkit.inventory.ItemStack clicked = event.getCurrentItem();

        if (clicked == null || !clicked.hasItemMeta() || !clicked.getItemMeta().hasLore()) return;

        // ★追加: プレイヤーのデータから、現在報告待ちのクエストを逆引きする
        PlayerData stats = plugin.getPlayerManager().getStats(player);
        if (stats == null || stats.currentQuestId == null || stats.currentQuestId.isEmpty()) return;

        SAOQuestData quest = plugin.getQuestManager().getQuest(stats.currentQuestId);
        if (quest == null) return;

        String itemId = ChatColor.stripColor(clicked.getItemMeta().getLore().get(0));
        org.bukkit.inventory.ItemStack finalItem = null;
        sac.plugin.info.SACplugin.item.SAOWeapon weapon = plugin.getSaoItemManager().getWeapon(itemId);

        if (weapon != null) {
            finalItem = plugin.getSaoItemManager().createWeaponItem(weapon);
        } else {
            sac.plugin.info.SACplugin.item.SAOGeneralItemData genItem = plugin.getSaoItemManager().getGeneralItem(itemId);
            if (genItem != null) {
                finalItem = plugin.getSaoItemManager().createGeneralItem(genItem);
            }
        }

        if (finalItem != null) {
            // ==========================================
            // ★追加: 報酬を選んだ「この瞬間」にクリア処理を行う！
            // ==========================================
            stats.col += quest.rewardCol;
            stats.exp += quest.rewardExp;

            // ★追加: クエスト報酬による武器種(体術など)の解放
            grantSkillUnlockIfAny(player, stats, quest);

            if (stats.questCounts == null) stats.questCounts = new HashMap<>();
            int currentClearCount = stats.questCounts.getOrDefault(quest.id, 0);
            stats.questCounts.put(quest.id, currentClearCount + 1);

            // クエスト完了としてデータをリセットする
            stats.currentQuestId = "";
            stats.questProgress = 0;
            stats.questCompleted = false;

            // ★重要: リセットした "後" に画面を閉じる (警告メッセージ回避のため)
            player.closeInventory();

            HashMap<Integer, org.bukkit.inventory.ItemStack> over = player.getInventory().addItem(finalItem);
            for (org.bukkit.inventory.ItemStack drop : over.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }

            String displayName = finalItem.hasItemMeta() && finalItem.getItemMeta().hasDisplayName()
                    ? finalItem.getItemMeta().getDisplayName() : itemId;

            // まとめて報酬メッセージを出す
            player.sendMessage("§e[System] §f" + quest.rewardCol + " Col / " + quest.rewardExp + " EXP");
            player.sendMessage("§b[System] §f" + displayName + " §aを受け取った！");

            new SAOMobListener(plugin).processLevelUp(player, stats);

            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
            plugin.getSaoScoreboard().updateScoreboard(player, stats);
            plugin.getPlayerManager().savePlayerData(player, stats);
        }
    }
    // ==========================================
    // ★追加: 報酬を選ばずにメニューを閉じた時の警告処理
    // ==========================================
    @EventHandler
    public void onRewardMenuClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        if (!event.getView().getTitle().equals("§b《Reward Selection》")) return;

        Player player = (Player) event.getPlayer();
        PlayerData stats = plugin.getPlayerManager().getStats(player);

        // クエストデータがリセットされず、完了報告待ちのまま残っている＝途中で画面を閉じたと判定
        if (stats != null && stats.currentQuestId != null && !stats.currentQuestId.isEmpty() && stats.questCompleted) {
            player.sendMessage("§b[System] §r§c報酬を選択しないとクエストは完了しません。");
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
        }
    }
    // ★追加: クエスト管理GUIのクリック処理
    @EventHandler
    public void onQuestAdminMenuClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();

        // ★修正: getLocalizedName()はPaper 1.21.1で信頼できないため、PersistentDataContainerで読む
        org.bukkit.NamespacedKey floorKey = new org.bukkit.NamespacedKey(plugin, "quest_admin_floor");
        org.bukkit.NamespacedKey idKey = new org.bukkit.NamespacedKey(plugin, "quest_admin_id");

        // 階層選択メニュー
        if (title.equals("§b《Quest Floor Selection》")) {
            event.setCancelled(true);
            if (event.getCurrentItem() == null || !event.getCurrentItem().hasItemMeta()) return;

            Integer floor = event.getCurrentItem().getItemMeta().getPersistentDataContainer()
                    .get(floorKey, org.bukkit.persistence.PersistentDataType.INTEGER);
            if (floor != null) {
                plugin.getQuestManager().openQuestListMenu((Player) event.getWhoClicked(), floor);
            }
        }
        // クエスト一覧メニュー
        else if (title.startsWith("§b《Floor ")) {
            event.setCancelled(true);
            if (event.getCurrentItem() == null || !event.getCurrentItem().hasItemMeta()) return;

            String questId = event.getCurrentItem().getItemMeta().getPersistentDataContainer()
                    .get(idKey, org.bukkit.persistence.PersistentDataType.STRING);
            if (questId != null) {
                Player player = (Player) event.getWhoClicked();
                player.closeInventory();
                plugin.getQuestManager().spawnQuestNPC(player, questId);
            }
        }
    }
}
