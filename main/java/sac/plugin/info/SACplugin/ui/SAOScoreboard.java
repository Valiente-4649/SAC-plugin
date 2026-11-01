package sac.plugin.info.SACplugin.ui;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scoreboard.*;
import sac.plugin.info.SACplugin.Player.PlayerData;
import sac.plugin.info.SACplugin.SACplugin;
import sac.plugin.info.SACplugin.item.SAOWeapon;
import sac.plugin.info.SACplugin.skill.SwordSkillData;

public class SAOScoreboard { // 名前を SAOScoreboard に変更
    private final SACplugin plugin;
    public SAOScoreboard(SACplugin plugin) {
        this.plugin = plugin;
    }

    // スコアボードを新しく作成してプレイヤーにセットする
    public void setupScoreboard(Player player, PlayerData data) {
        try {
            ScoreboardManager manager = Bukkit.getScoreboardManager();
            Scoreboard board = manager.getNewScoreboard();

            // 目標（サイドバー）の作成
            // "SAOStats" という内部名で、タイトルを "Sword Art Online" に
            // ★ 1.21.1対応: String版は内部で非推奨化が進んでいるためComponent版を使用
            Objective obj = board.registerNewObjective(
                    "SAOStats",
                    Criteria.DUMMY,
                    net.kyori.adventure.text.Component.text("Sword Art Craft")
                            .color(net.kyori.adventure.text.format.NamedTextColor.GOLD)
            );
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);

            player.setScoreboard(board);

            // 初回の表示更新
            updateScoreboard(player, data);
        } catch (Exception e) {
            plugin.getLogger().severe("[SAO] " + player.getName() + " のsetupScoreboardで例外発生！");
            e.printStackTrace();
        }
    }

    // 数値を最新の状態に更新する
    public void updateScoreboard(Player player, PlayerData data) {
        Scoreboard board = player.getScoreboard();
        Objective obj = board.getObjective("SAOStats");

        // もしスコアボードがなければ作り直す
        if (obj == null) {
            setupScoreboard(player, data);
            return;
        }

        // ---【重要：ここが更新の鍵】---
        // 前の数値を消さないと、新しい数値が重なって表示されてしまいます
        for (String entry : board.getEntries()) {
            board.resetScores(entry);
        }

        String weaponId = null;
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        if (mainHand != null && mainHand.hasItemMeta() && mainHand.getItemMeta().hasLore()) {
            // Loreの1行目にIDが入っている仕様に合わせます
            weaponId = org.bukkit.ChatColor.stripColor(mainHand.getItemMeta().getLore().get(0));
        }

        String skillName = "§7None";
        if (data.selectedSkillId != null && !data.selectedSkillId.isEmpty()) {
            SwordSkillData skill = plugin.getSwordSkillManager().getSkill(data.selectedSkillId);
            if (skill != null) {
                skillName = "§e" + skill.name;
            }
        }

        // ==========================================
        // ★修正: スコアボードの目標EXPも二次関数のマゾ仕様に合わせる！
        // ★変更: 係数を50→35に変更(SAOMobListenerのnextLevelExpと必ず同じ式にすること)
        // ==========================================
        // ★変更: 10レベルごとに必要経験値+500を追加(SAOMobListenerのnextLevelExpと必ず同じ式にすること)
        int displayMaxExp = 500 + (data.level * (data.level - 1) * 75) + (data.level / 10) * 500;
        int hp = (int) data.currentHP;
        int max = (int) data.maxHP;

        String hpLine = "§fHP: §a" + hp + " §7/ §a" + max;
        // ★追加: 満腹ゲージの表示(30%未満で色を変えて警告)
        int hungerPercent = (int) data.hunger;
        String hungerColor = hungerPercent < 10 ? "§4" : (hungerPercent < 30 ? "§c" : "§6");
        String hungerLine = "§fHunger: " + hungerColor + hungerPercent + "%";

        // ゲーム内時刻を取得
        String gameTime = "??:??";
        if (plugin.getTimeManager() != null) {
            gameTime = plugin.getTimeManager().getGameTimeString();
        }

        // 各行を設定 (数字は上からの順番)
        // ★追加: 防具のSTR/AGIボーナス(金属防具鍛造)がある場合は「+N」を併記する
        String strLine = "§fSTR: §6" + data.str + (data.armorStrBonus > 0 ? " §a(+" + data.armorStrBonus + ")" : "");
        String agiLine = "§fAGI: §b" + data.agi + (data.armorAgiBonus > 0 ? " §a(+" + data.armorAgiBonus + ")" : "");

        int vitalityPercent = (int) Math.ceil(data.vitality);
        String vitalityColor = data.vitality <= 10 ? "§c" : "§a";
        obj.getScore("§fVitality: " + vitalityColor + vitalityPercent + "%").setScore(0);
        obj.getScore("§7--------------------").setScore(15);
        obj.getScore("§fName: §b" + player.getName()).setScore(14);
        obj.getScore("§fLevel: §e" + data.level).setScore(13);
        obj.getScore(hpLine).setScore(12);
        obj.getScore(hungerLine).setScore(11);
        obj.getScore("§fEXP: §e" + data.exp + " §7/ §e" + displayMaxExp).setScore(10);
        obj.getScore("§fCol: §6" + data.col).setScore(9);
        obj.getScore(" ").setScore(8);
        obj.getScore(strLine).setScore(7);
        obj.getScore(agiLine).setScore(6);
        obj.getScore("§fSkillPoints: §e" + data.skillPoint).setScore(5);
        obj.getScore("  ").setScore(4);
        obj.getScore("§fSET SKILL: §e" + skillName).setScore(3);
        obj.getScore("§fTime: §b" + gameTime).setScore(2);
        obj.getScore("§7--------------------  ").setScore(1);

        // ==========================================
        // ★追加: スコアボード更新と同時に、頭上とTabリストのHPバーも更新
        // ==========================================
        //sac.plugin.info.SACplugin.ui.SAOHPDisplay.updatePlayerHPBar(player, data.currentHP, data.maxHP);
    }
}