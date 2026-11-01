package sac.plugin.info.SACplugin.skill;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import sac.plugin.info.SACplugin.Player.PlayerData;
import sac.plugin.info.SACplugin.SACplugin;

public class SkillMenuListener implements Listener {
    private final SACplugin plugin;

    public SkillMenuListener(SACplugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();

        // ==========================================
        // 1. メインメニュー
        // ==========================================
        if (title.equals("§b《 Skill Menu 》")) {
            event.setCancelled(true);
            int slot = event.getRawSlot();

            // ★追加: 入れ替えモードへ
            if (slot == 25) {
                player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                SkillMenuManager.openSlotSwapMenu(player, plugin, -1);
                return;
            }
            // 既存: 設定モードへ
            if (slot == 26) {
                player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                SkillMenuManager.openSlotEditMenu(player, plugin);
                return;
            }

            if (slot >= 10 && slot <= 15) {
                PlayerData stats = plugin.getPlayerManager().getStats(player);
                String skill = stats.equippedSkills[slot - 10];
                // ★変更: ハードコードされた武器種名リストの代わりに、weapon_types.jsonに
                // 登録されている武器種かどうかで判定する（武器種追加時に自動対応するため）
                if (plugin.getWeaponTypeManager().getAllTypes().containsKey(skill)) {
                    player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                    SkillMenuManager.openSubMenu(player, skill, plugin);
                } else if ("疾走".equals(skill)) {
                    // ★追加: 疾走スロットのクリックは実行確認メニューを開く
                    player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                    SkillMenuManager.openDashExecuteMenu(player, plugin);
                } else if (!skill.isEmpty()) {
                    player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                }
            }
            return;
        }

        // ==========================================
        // ★新規追加: 2. スロット入れ替えモードの処理
        // ==========================================
        if (title.equals("§b《 Skill Swap - 入れ替え 》")) {
            event.setCancelled(true);
            int slot = event.getRawSlot();

            if (slot == 26) { // 戻るボタン
                player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                SkillMenuManager.openMainMenu(player, plugin);
                return;
            }

            if (slot >= 10 && slot <= 15) {
                int clickedIndex = slot - 10;

                // ★追加: レベル未達で解放されていないスロットはクリックしても無視する
                PlayerData clickStats = plugin.getPlayerManager().getStats(player);
                int available = SkillMenuManager.getAvailableSlotCount(clickStats, plugin);
                if (clickedIndex >= available) {
                    player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                    return;
                }

                // 戻るボタンのLoreに隠した「現在選択中のスロット」を取得
                ItemStack backBtn = event.getInventory().getItem(26);
                int selectedIndex = -1;
                if (backBtn != null && backBtn.hasItemMeta() && backBtn.getItemMeta().hasLore()) {
                    for (String line : backBtn.getItemMeta().getLore()) {
                        if (line.startsWith("§0selected:")) {
                            selectedIndex = Integer.parseInt(line.replace("§0selected:", ""));
                        }
                    }
                }

                if (selectedIndex == -1) {
                    // 1回目のクリック (選択)
                    player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                    SkillMenuManager.openSlotSwapMenu(player, plugin, clickedIndex);
                } else if (selectedIndex == clickedIndex) {
                    // 同じ枠をクリック (選択解除)
                    player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                    SkillMenuManager.openSlotSwapMenu(player, plugin, -1);
                } else {
                    // ★ 2回目のクリック (入れ替え実行！)
                    PlayerData stats = plugin.getPlayerManager().getStats(player);

                    // スロットの文字列を入れ替える
                    String temp = stats.equippedSkills[selectedIndex];
                    stats.equippedSkills[selectedIndex] = stats.equippedSkills[clickedIndex];
                    stats.equippedSkills[clickedIndex] = temp;

                    plugin.getPlayerManager().savePlayerData(player, stats);

                    player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                    player.sendMessage("§b[System] §r§aスロット " + (selectedIndex + 1) + " と スロット " + (clickedIndex + 1) + " を入れ替えました");

                    // 選択を解除して画面を更新
                    SkillMenuManager.openSlotSwapMenu(player, plugin, -1);
                }
            }
            return;
        }

        // ==========================================
        // 3. スロット設定モード
        // ==========================================
        if (title.equals("§b《 Skill Edit - スロット選択 》")) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            if (slot >= 10 && slot <= 15) {
                PlayerData stats = plugin.getPlayerManager().getStats(player);
                int clickedIndex = slot - 10;

                // ★追加: レベル未達で解放されていないスロットはクリックしても無視する
                int available = SkillMenuManager.getAvailableSlotCount(stats, plugin);
                if (clickedIndex >= available) {
                    player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                    return;
                }

                player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                SkillMenuManager.openSkillCatalogMenu(player, stats, clickedIndex, plugin);
            }
            return;
        }

        // ==========================================
        // 4. スキルカタログ（入れるスキルを選ぶ）
        // ==========================================
        if (title.startsWith("§b《 Skill Catalog - スロット ")) {
            event.setCancelled(true);
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || !clicked.hasItemMeta()) return;

            int targetSlot;
            try {
                targetSlot = Integer.parseInt(title.replaceAll("[^0-9]", "")) - 1;
            } catch (Exception e) { return; }

            String newSkill = ChatColor.stripColor(clicked.getItemMeta().getDisplayName());
            PlayerData stats = plugin.getPlayerManager().getStats(player);

            // 既に他のスロットに入っていないかチェック
            for (int i = 0; i < stats.equippedSkills.length; i++) {
                if (newSkill.equals(stats.equippedSkills[i])) {
                    player.sendMessage("§b[System] §r§cそのスキルは既にスロット " + (i + 1) + " にセットされています");
                    player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                    return;
                }
            }

            String oldSkill = stats.equippedSkills[targetSlot];
            if (oldSkill != null && !oldSkill.isEmpty()) {
                player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                SkillMenuManager.openConfirmReplaceMenu(player, targetSlot, oldSkill, newSkill);
            } else {
                stats.equippedSkills[targetSlot] = newSkill;
                plugin.getPlayerManager().savePlayerData(player, stats);
                player.sendMessage("§b[System] §r§aスロット " + (targetSlot + 1) + " に §e" + newSkill + " §aをセットしました");
                player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                SkillMenuManager.openMainMenu(player, plugin);
            }
            return;
        }

        // ==========================================
        // 5. 入れ替え(上書き)確認画面
        // ==========================================
        if (title.equals("§c《 スキル上書き確認 》")) {
            event.setCancelled(true);
            int slot = event.getRawSlot();

            if (slot == 2) { // [ はい ]
                ItemStack info = event.getInventory().getItem(4);
                int targetSlot = -1; String oldS = "", newS = "";
                for(String line : info.getItemMeta().getLore()) {
                    if(line.startsWith("§0slot:")) targetSlot = Integer.parseInt(line.replace("§0slot:", ""));
                    if(line.startsWith("§0old:")) oldS = line.replace("§0old:", "");
                    if(line.startsWith("§0new:")) newS = line.replace("§0new:", "");
                }

                PlayerData stats = plugin.getPlayerManager().getStats(player);
                SkillMenuManager.resetSkillProficiency(stats, oldS);
                stats.equippedSkills[targetSlot] = newS;
                plugin.getPlayerManager().savePlayerData(player, stats);

                player.sendMessage("§c" + oldS + " §aを外して熟練度をリセットし、§e" + newS + " §aをセットしました");
                player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                SkillMenuManager.openMainMenu(player, plugin);

            } else if (slot == 6) { // [ いいえ ]
                player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                SkillMenuManager.openSlotEditMenu(player, plugin);
            }
            return;
        }

        // ==========================================
        // ★新規追加: 疾走スキル実行メニュー
        // ==========================================
        if (title.equals("§b《 疾走スキル 》")) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            if (slot != 22) return;

            PlayerData stats = plugin.getPlayerManager().getStats(player);
            if (stats == null) return;

            boolean isActive = "dash".equals(stats.selectedSkillId);
            if (isActive) {
                // 解除: ソードスキルは未選択状態に戻す
                stats.selectedSkillId = "";
                player.sendMessage("§b[System] §r§7疾走を解除しました");
            } else {
                // ★実行: ソードスキルの代わりに疾走をセットする
                stats.selectedSkillId = "dash";
                player.sendMessage("§b[System] §r§a疾走§aをセットしました！移動速度が§b2倍§aになります");
            }
            plugin.getPlayerManager().savePlayerData(player, stats);
            // ★速度計算を即座に反映させる
            plugin.getAgiListener().updateMovementSpeed(player);
            if (plugin.getSaoScoreboard() != null) plugin.getSaoScoreboard().updateScoreboard(player, stats);

            player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
            player.closeInventory();
            return;
        }

        // ==========================================
        // 6. ソードスキル選択画面（既存機能 + 体術）
        // ==========================================
        if (title.startsWith("スキル選択: ")) {
            event.setCancelled(true);
            if (plugin.getSkillListener().isProcessing(player)) {
                player.sendMessage("§b[System] §r§cスキルが完全に再使用可能になるまで切り替えはできません");
                player.closeInventory();
                return;
            }
            if (event.getCurrentItem() == null || event.getCurrentItem().getType() == Material.AIR || !event.getCurrentItem().hasItemMeta()) return;

            // ★ 鉄格子（未解放スキル）はクリックしても何もしない
            if (event.getCurrentItem().getType() == Material.IRON_BARS) {
                player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                player.sendMessage("§b[System] §r§cまだ解放されていないスキルです！");
                return;
            }

            // LoreのIDを優先取得
            String skillId = null;
            if (event.getCurrentItem().getItemMeta().hasLore()) {
                for (String line : event.getCurrentItem().getItemMeta().getLore()) {
                    if (line.startsWith("§0id:")) {
                        skillId = line.replace("§0id:", "");
                        break;
                    }
                }
            }
            if (skillId == null) {
                String displayName = event.getCurrentItem().getItemMeta().getDisplayName();
                skillId = org.bukkit.ChatColor.stripColor(displayName).toLowerCase().replace(" ", "_");
            }

            PlayerData stats = plugin.getPlayerManager().getStats(player);
            if (stats == null) return;

            // ★ 通常ソードスキル
            SwordSkillData skillData = plugin.getSwordSkillManager().getSkill(skillId);
            if (stats != null && skillData != null) {
                int currentProf = stats.proficiency.getOrDefault(skillData.category, 1);
                if (currentProf < skillData.requiredProficiency) {
                    player.sendMessage("§c" + skillData.category + "の熟練度が足りないため、セットできません");
                    return;
                }
                stats.selectedSkillId = skillId;
                plugin.getPlayerManager().savePlayerData(player, stats);
                // ★修正: savePlayerData()はupdateMovementSpeed()を呼ばないため、
                // 体術など「選択中のスキルで速度が変わる」ケースに対応できるようここで明示的に呼ぶ
                plugin.getAgiListener().updateMovementSpeed(player);
                player.sendMessage("§b[System] §r§aソードスキル《 " + skillData.name + " §a》をセットしました");
                player.playSound(player.getLocation(), "sao.menu.click", 1.0f, 1.0f);
                if (plugin.getSaoScoreboard() != null) plugin.getSaoScoreboard().updateScoreboard(player, stats);
                player.closeInventory();
            }
        }
    }
}