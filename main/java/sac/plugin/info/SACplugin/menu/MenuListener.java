package sac.plugin.info.SACplugin.menu;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import sac.plugin.info.SACplugin.Player.PlayerData;
import sac.plugin.info.SACplugin.SACplugin;
import sac.plugin.info.SACplugin.skill.SkillMenuManager;

import java.util.Arrays;

public class MenuListener implements Listener {
    private final SACplugin plugin;

    // 装備を置けるスロットの番号
    private final int[] EQUIP_SLOTS = {0, 3, 9, 12, 18, 21};

    public MenuListener(SACplugin plugin) {
        this.plugin = plugin;
    }

    public void openMainMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, "§b《Menu》§f\uF808\uE001");
        PlayerData data = plugin.getPlayerManager().getStats(player);

        if (data != null) {
            if (data.equipment.containsKey("head")) inv.setItem(0, data.equipment.get("head"));
            if (data.equipment.containsKey("chest")) inv.setItem(3, data.equipment.get("chest"));
            if (data.equipment.containsKey("arm")) inv.setItem(9, data.equipment.get("arm"));
            if (data.equipment.containsKey("boots")) inv.setItem(12, data.equipment.get("boots"));
            if (data.equipment.containsKey("legs")) inv.setItem(18, data.equipment.get("legs"));
            if (data.equipment.containsKey("accessory")) inv.setItem(21, data.equipment.get("accessory"));
        }

        inv.setItem(5, createInvisibleButton("§aStatus / Stats", 5));
        inv.setItem(7, createInvisibleButton("§bSkill Menu", 7));
        inv.setItem(14, createInvisibleButton("§dFriend/Party", 14));
        inv.setItem(23, createInvisibleButton("§eChat/Guild (未実装)", 23));

        for (int i = 0; i < 27; i++) {
            if (isEquipSlot(i)) continue;
            if (inv.getItem(i) == null || inv.getItem(i).getType() == Material.AIR) {
                inv.setItem(i, createBackgroundItem());
            }
        }

        player.openInventory(inv);
        player.playSound(player.getLocation(), "sao.menu.open", 1.0f, 1.0f);
    }

    private ItemStack createInvisibleButton(String name, int customModelData) {
        ItemStack btn = new ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = btn.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(Arrays.asList("§7クリックして開く"));
        meta.setCustomModelData(9000 + customModelData);
        btn.setItemMeta(meta);
        return btn;
    }

    private ItemStack createBackgroundItem() {
        ItemStack bg = new ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = bg.getItemMeta();
        meta.setDisplayName("§f");
        meta.setCustomModelData(9000);
        bg.setItemMeta(meta);
        return bg;
    }

    private boolean isEquipSlot(int slot) {
        for (int s : EQUIP_SLOTS) {
            if (s == slot) return true;
        }
        return false;
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!event.getView().getTitle().equals("§b《Menu》§f\uF808\uE001")) return;

        // ★【最重要】ドラッグ＆ドロップ、Shift移動、数字キー移動など、マイクラの基本動作をすべて強制キャンセル！
        event.setCancelled(true);

        Player player = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        Inventory topInv = event.getView().getTopInventory();
        ItemStack clickedItem = event.getCurrentItem();

        // 虚空や空っぽの枠をクリックした場合は無視
        if (clickedItem == null || clickedItem.getType() == Material.AIR) return;

        // ==========================================
        // 1. メニュー側（上半分の画面）をクリックした場合
        // ==========================================
        if (slot < 27) {
            // (A) ボタンの処理
            if (slot == 7) {
                player.closeInventory();
                SkillMenuManager.openMainMenu(player, plugin);
            } else if (slot == 14) {
                player.closeInventory();
                plugin.getPartyMenuListener().openPartyMainMenu(player);
            } else if (slot == 5) {
                // ★追加: Statsボタンを押したら選択メニューへ
                player.closeInventory();
                player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                openStatsChoiceMenu(player);
            } else if (slot == 23) {
                player.sendMessage("§b[System] §r§cFEATURE UNAVAILABLE");
            }
            // (B) 装備している防具をクリックして「外す」処理
            else if (isEquipSlot(slot)) {
                // 手持ちのインベントリに空きがあるか確認
                if (player.getInventory().firstEmpty() == -1) {
                    player.sendMessage("§b[System] §r§cインベントリが一杯で外せません！");
                    player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                    return;
                }
                // 手持ちに返却して、メニューの枠を空にする
                player.getInventory().addItem(clickedItem.clone());
                topInv.setItem(slot, null);
                player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
            }
        }
        // ==========================================
        // 2. プレイヤーの手持ち側（下半分の画面）をクリックした場合
        // ==========================================
        else {
            if (!clickedItem.hasItemMeta() || !clickedItem.getItemMeta().hasLore()) return;

            String equipType = "";
            int requires = 1;

            // Loreから「Equip: (部位)」と「Requires: (レベル)」を読み取る
            for (String line : clickedItem.getItemMeta().getLore()) {
                String plain = org.bukkit.ChatColor.stripColor(line);
                if (plain.startsWith("Equip: ")) equipType = plain.replace("Equip: ", "").trim();
                else if (plain.startsWith("Requires: ")) {
                    try { requires = Integer.parseInt(plain.replace("Requires: ", "").trim()); } catch (Exception ignored) {}
                }
            }

            // 「Equip:」の表記がないアイテム（武器やリンゴなど）をクリックした場合は無視
            if (equipType.isEmpty()) return;

            // ★ 装備レベルチェック(アクセサリーのREQボーナス分だけ緩和される)
            PlayerData data = plugin.getPlayerManager().getStats(player);
            if (data != null && data.level + data.accReqBonus < requires) {
                player.sendMessage("§b[System] §r§cレベルが足りないため装備できません！(要求: " + requires + ")");
                player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                return;
            }

            // 部位（HEAD, CHEST...）に応じた正しい装備スロット番号を判定
            int targetSlot = -1;
            switch (equipType.toUpperCase()) {
                case "HEAD": targetSlot = 0; break;
                case "CHEST": targetSlot = 3; break;
                case "ARM": targetSlot = 9; break;
                case "BOOTS": targetSlot = 12; break;
                case "LEGS": targetSlot = 18; break;
                case "ACCESSORY": targetSlot = 21; break;
            }

            // 正しい部位が見つかったら、自動で装着（すでにある場合は入れ替え）！
            if (targetSlot != -1) {
                ItemStack existing = topInv.getItem(targetSlot); // 今着ている装備
                topInv.setItem(targetSlot, clickedItem.clone()); // 新しい装備をメニューにセット
                event.setCurrentItem(existing); // クリックした場所にあった新しい装備を、古い装備に置き換える(なければ空になる)
                player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
            }
        }
    }

    @EventHandler
    public void onMenuClose(InventoryCloseEvent event) {
        if (!event.getView().getTitle().equals("§b《Menu》§f\uF808\uE001")) return;

        Player player = (Player) event.getPlayer();
        PlayerData data = plugin.getPlayerManager().getStats(player);
        if (data == null) return;

        Inventory inv = event.getInventory();

        saveEquip(data, "head", inv.getItem(0));
        saveEquip(data, "chest", inv.getItem(3));
        saveEquip(data, "arm", inv.getItem(9));
        saveEquip(data, "boots", inv.getItem(12));
        saveEquip(data, "legs", inv.getItem(18));
        saveEquip(data, "accessory", inv.getItem(21));

        plugin.getPlayerManager().savePlayerData(player, data);
        plugin.getPlayerManager().syncHealth(player, data);

        player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
    }

    private void saveEquip(PlayerData data, String key, ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            data.equipment.remove(key);
        } else {
            data.equipment.put(key, item);
        }
    }

    @EventHandler
    public void onMenuUse(PlayerInteractEvent event) {
        // ★追加: オフハンドによる2重発火(音の二重再生の原因)を防ぐ
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;

        Player player = event.getPlayer();
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType() == Material.AIR) return;
        if (!item.hasItemMeta() || !item.getItemMeta().hasDisplayName()) return;

        if (item.getType() == Material.PRISMARINE_SHARD && item.getItemMeta().getDisplayName().equals("§b《Menu》")) {
            event.setCancelled(true);
            openMainMenu(player);
        }
    }

    // ==========================================
    // バニラインベントリでの防具の保護
    // ==========================================
    @EventHandler
    public void onVanillaInventoryClick(InventoryClickEvent event) {
        if (event.getView().getTitle().equals("§b《Menu》§f\uF808\uE001")) return;

        // 1. バニラの防具スロットを直接触ろうとした場合
        if (event.getSlotType() == InventoryType.SlotType.ARMOR) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player) {
                ((Player) event.getWhoClicked()).sendMessage("§b[System] §r§c防具はメニュー(《Menu》)から着脱してください！");
            }
        }

        // 2. サバイバルインベントリでのみ、Shiftクリックによる強制装備を防ぐ
        if (event.isShiftClick() && event.getInventory().getType() == InventoryType.CRAFTING) {
            ItemStack item = event.getCurrentItem();
            if (item != null && item.hasItemMeta() && item.getItemMeta().hasLore()) {
                for (String line : item.getItemMeta().getLore()) {
                    if (org.bukkit.ChatColor.stripColor(line).startsWith("Equip: ")) {
                        event.setCancelled(true);
                        if (event.getWhoClicked() instanceof Player) {
                            ((Player) event.getWhoClicked()).sendMessage("§b[System] §r§c防具はメニュー(《Menu》)から装備してください！");
                        }
                        break;
                    }
                }
            }
        }
    }
    // ==========================================
    // ★追加: ステータス選択画面
    // ==========================================
    public void openStatsChoiceMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, "§b《 Stats 》");

        ItemStack checkBtn = new ItemStack(Material.BOOK);
        ItemMeta checkMeta = checkBtn.getItemMeta();
        checkMeta.setDisplayName("§aステータスを確認する");
        checkMeta.setLore(Arrays.asList("§7チャット欄に現在のステータスを表示します"));
        checkBtn.setItemMeta(checkMeta);
        inv.setItem(11, checkBtn);

        ItemStack pointBtn = new ItemStack(Material.EXPERIENCE_BOTTLE);
        ItemMeta pointMeta = pointBtn.getItemMeta();
        pointMeta.setDisplayName("§dスキルポイントを振り分ける");
        pointMeta.setLore(Arrays.asList("§7STRかAGIにポイントを振ります"));
        pointBtn.setItemMeta(pointMeta);
        inv.setItem(15, pointBtn);

        // ★追加: 実績確認リストへのアイコン
        ItemStack achBtn = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta achMeta = achBtn.getItemMeta();
        achMeta.setDisplayName("§6実績を確認する");
        achMeta.setLore(Arrays.asList("§7獲得した実績の確認と、称号の設定ができます"));
        achBtn.setItemMeta(achMeta);
        inv.setItem(13, achBtn);

        player.openInventory(inv);
    }

    // ==========================================
    // ★追加: ステータス振り分け画面
    // ==========================================
    public void openPointAllocationMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, "§b《 Point Allocation 》");
        PlayerData data = plugin.getPlayerManager().getStats(player);
        if (data == null) return;

        // ==========================================
        // ★追加: AGIによる「瞬歩(しゅんぽ)」クールタイム短縮の計算
        // 基本3.0秒から、AGI3につき0.1秒短縮(最低0.5秒)
        // ==========================================
        long stepCooldownMs = Math.max(500L, 3000L - (data.agi / 3) * 100L);
        String stepCooldownStr = String.format("%.1f", stepCooldownMs / 1000.0);

        ItemStack infoBtn = new ItemStack(Material.PAPER);
        ItemMeta infoMeta = infoBtn.getItemMeta();
        infoMeta.setDisplayName("§e現在のステータス");
        infoMeta.setLore(Arrays.asList(
                "§7残りスキルポイント: §b" + data.skillPoint,
                "",
                "§cSTR: " + data.str + " §7(筋力　武器や防具の重さ軽減)",
                "§aAGI: " + data.agi + " §7(俊敏　移動速度・回避可能レベル上昇)",
                "§7　└ §bステップ§7のクールタイム: §f" + stepCooldownStr + "秒",
                "§7　　 AGI3ごとに0.1秒短縮、最速0.5秒)"
        ));
        infoBtn.setItemMeta(infoMeta);
        inv.setItem(13, infoBtn);

        ItemStack strBtn = new ItemStack(Material.RED_DYE);
        ItemMeta strMeta = strBtn.getItemMeta();
        strMeta.setDisplayName("§cSTR に振る (+1)");
        strMeta.setLore(Arrays.asList("§7クリックでSTRを1上げます"));
        strBtn.setItemMeta(strMeta);
        inv.setItem(11, strBtn);

        ItemStack agiBtn = new ItemStack(Material.LIME_DYE);
        ItemMeta agiMeta = agiBtn.getItemMeta();
        agiMeta.setDisplayName("§aAGI に振る (+1)");
        agiMeta.setLore(Arrays.asList("§7クリックでAGIを1上げます"));
        agiBtn.setItemMeta(agiMeta);
        inv.setItem(15, agiBtn);

        // ★追加: スキルポイントリセットボタン
        long now = System.currentTimeMillis();
        long cooldownMs = 50L * 24 * 60 * 60 * 1000L; // 50日
        long elapsed = now - data.lastSkillResetTime;
        boolean canReset = data.lastSkillResetTime == 0L || elapsed >= cooldownMs;

        ItemStack resetBtn;
        if (canReset) {
            resetBtn = new ItemStack(Material.RECOVERY_COMPASS);
            ItemMeta resetMeta = resetBtn.getItemMeta();
            resetMeta.setDisplayName("§eスキルポイントリセット");
            resetMeta.setLore(Arrays.asList(
                    "§7STR・AGIをすべてリセットし、",
                    "§7スキルポイントを元に戻します。",
                    "",
                    "§c一度実行すると50日間再使用できません。",
                    "",
                    "§aクリックで実行"
            ));
            resetBtn.setItemMeta(resetMeta);
        } else {
            long remainMs = cooldownMs - elapsed;
            long remainDays = remainMs / (24 * 60 * 60 * 1000L);
            long remainHours = (remainMs % (24 * 60 * 60 * 1000L)) / (60 * 60 * 1000L);
            resetBtn = new ItemStack(Material.GRAY_DYE);
            ItemMeta resetMeta = resetBtn.getItemMeta();
            resetMeta.setDisplayName("§8スキルポイントリセット §7(クールタイム中)");
            resetMeta.setLore(Arrays.asList(
                    "§7残り §c" + remainDays + "日 " + remainHours + "時間 §7後に再使用可能です。"
            ));
            resetBtn.setItemMeta(resetMeta);
        }
        inv.setItem(22, resetBtn);

        player.openInventory(inv);
    }
    // ==========================================
    @EventHandler
    public void onStatsMenuClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();
        if (!title.equals("§b《 Stats 》") && !title.equals("§b《 Point Allocation 》")) return;

        event.setCancelled(true); // アイテムの移動を禁止
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();

        PlayerData data = plugin.getPlayerManager().getStats(player);
        if (data == null) return;

        if (title.equals("§b《 Stats 》")) {
            if (slot == 13) {
                // ★追加: 実績確認リストを開く
                player.closeInventory();
                plugin.getAchievementMenu().openOwn(player);
                return;
            }
            if (slot == 11) {
                // 1. チャットに表示
                player.closeInventory();
                player.sendMessage("§6===============[ Status ]===============");
                player.sendMessage("§f名前: §a" + player.getName());
                player.sendMessage("§§r§fレベル: §e" + data.level);
                player.sendMessage("§fHP: §c" + (int)data.currentHP + " §f/ §c" + (int)data.maxHP);
                player.sendMessage("§fCol: §6" + data.col);
                player.sendMessage("§r§fSTR: §c" + data.str);
                player.sendMessage("§r§fAGI: §b" + data.agi);

                // ==========================================
                // ★追加: 命中率の表示。手持ち武器のAccuracyボーナスから算出する。
                // 基本命中率80%(ミス率20%)、Accuracyボーナス1につき命中率+1%(上限100%)。
                // ==========================================
                org.bukkit.inventory.ItemStack mainHandForAcc = player.getInventory().getItemInMainHand();
                int accuracyBonus = sac.plugin.info.SACplugin.Smithing.ForgeManager.getAccuracyBonus(mainHandForAcc) + data.accAccuracyBonus;
                int accuracyPercent = Math.max(0, Math.min(100, 80 + accuracyBonus)
                        - sac.plugin.info.SACplugin.life.VitalityManager.accuracyPenalty(data));
                player.sendMessage("§b[System] §r§f命中率: §b" + accuracyPercent + "%");

                // ★新要素: 受注中のクエストの名前・ターゲット・必要数を表示
                if (data.currentQuestId != null && !data.currentQuestId.isEmpty()) {
                    sac.plugin.info.SACplugin.npc.SAOQuestData quest = plugin.getQuestManager().getQuest(data.currentQuestId);
                    if (quest != null) {
                        // ターゲット名を解決（MOBならモブ名、ITEMならアイテム名）
                        String targetName = quest.targetId;
                        if ("MOB".equals(quest.type)) {
                            sac.plugin.info.SACplugin.mob.SAOMobData mobData = plugin.getSaoMobManager().getMobData(quest.targetId);
                            if (mobData != null && mobData.name != null) targetName = mobData.name;
                        } else if ("ITEM".equals(quest.type)) {
                            sac.plugin.info.SACplugin.item.SAOGeneralItemData genItem = plugin.getSaoItemManager().getGeneralItem(quest.targetId);
                            if (genItem != null && genItem.name != null) targetName = genItem.name;
                        }

                        String actionLabel = "MOB".equals(quest.type) ? "討伐数" : "納品数";
                        String progressColor = data.questCompleted ? "§a" : "§e";

                        player.sendMessage("§f[ 受注中のクエスト ]");
                        player.sendMessage("§b[System] §r§fクエスト名: §b" + quest.questName);
                        player.sendMessage("§b[System] §r§fターゲット: §d" + targetName);
                        player.sendMessage("§f" + actionLabel + ": " + progressColor
                                + data.questProgress + " §f/ " + progressColor + quest.requiredAmount
                                + (data.questCompleted ? " §a(達成！NPCに報告しよう)" : ""));
                    }
                } else {
                    player.sendMessage("§f[ 受注中のクエスト ]: §7なし");
                }

                player.sendMessage("§f[ セットスキル ]");
                for (int i = 0; i < 6; i++) {
                    String skill = (data.equippedSkills[i] == null || data.equippedSkills[i].isEmpty()) ? "未セット" : data.equippedSkills[i];
                    player.sendMessage("§b[System] §r§fセットスキル" + (i + 1) + ": §d" + skill);
                }
                player.sendMessage("§6========================================");
                player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
            } else if (slot == 15) {
                // 2. 振り分け画面へ
                player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                openPointAllocationMenu(player);
            }
        }
        else if (title.equals("§b《 Point Allocation 》")) {
            if (slot == 11) {
                // STRに振る
                if (data.skillPoint > 0) {
                    data.skillPoint--;
                    data.str++;
                    plugin.getPlayerManager().savePlayerData(player, data);
                    plugin.getPlayerManager().syncHealth(player, data);
                    plugin.getSaoScoreboard().updateScoreboard(player, data);
                    // STR変更 → 攻撃速度を再計算
                    plugin.getAgiListener().updateMovementSpeed(player);
                    plugin.getStrListener().updateAttackSpeed(player);
                    player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                    openPointAllocationMenu(player);
                } else {
                    player.sendMessage("§b[System] §r§cスキルポイントが足りません！");
                    player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                }
            } else if (slot == 15) {
                // AGIに振る
                if (data.skillPoint > 0) {
                    data.skillPoint--;
                    data.agi++;
                    plugin.getPlayerManager().savePlayerData(player, data);
                    plugin.getPlayerManager().syncHealth(player, data);
                    plugin.getSaoScoreboard().updateScoreboard(player, data);
                    // AGI変更 → 移動速度を再計算
                    plugin.getAgiListener().updateMovementSpeed(player);
                    player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                    openPointAllocationMenu(player);
                } else {
                    player.sendMessage("§b[System] §r§cスキルポイントが足りません！");
                    player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                }
            } else if (slot == 22) {
                // ★追加: スキルポイントリセット
                long now = System.currentTimeMillis();
                long cooldownMs = 50L * 24 * 60 * 60 * 1000L; // 50日
                boolean canReset = data.lastSkillResetTime == 0L || (now - data.lastSkillResetTime) >= cooldownMs;

                if (!canReset) {
                    player.sendMessage("§b[System] §r§cスキルポイントリセットはクールタイム中です！");
                    player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                    return;
                }

                // STR・AGIをリセットし、スキルポイントをlevel-1に
                data.str = 0;
                data.agi = 0;
                data.skillPoint = Math.max(0, data.level - 1);
                data.lastSkillResetTime = now;

                plugin.getPlayerManager().savePlayerData(player, data);
                plugin.getPlayerManager().syncHealth(player, data);
                plugin.getSaoScoreboard().updateScoreboard(player, data);
                plugin.getAgiListener().updateMovementSpeed(player);
                plugin.getStrListener().updateAttackSpeed(player);

                player.sendMessage("§e[System] スキルポイントをリセットしました。");
                player.sendMessage("§b[System] §r§7STR: 0 / AGI: 0 / スキルポイント: §b" + data.skillPoint);
                player.sendMessage("§b[System] §r§c次回リセット可能: 50日後");
                player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                openPointAllocationMenu(player);
            }
        }
    }
}