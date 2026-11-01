package sac.plugin.info.SACplugin.ui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

public class SAOHPDisplay {
    // 1. 20分割のHPバー文字列( |||||| )を生成するメソッド
    public static String generateHpBar(double currentHp, double maxHp) {
        int maxBars = 20;
        int activeBars = (int) Math.ceil((currentHp / maxHp) * maxBars);
        if (activeBars < 0) activeBars = 0;
        if (activeBars > maxBars) activeBars = maxBars;

        String color = "§a"; // 基本は緑
        if (activeBars <= 4) {
            color = "§c"; // 残り4本以下で赤
        } else if (activeBars <= 8) {
            color = "§6"; // 残り8本以下でオレンジ
        }

        StringBuilder bar = new StringBuilder(color);
        for (int i = 0; i < activeBars; i++) {
            bar.append("|");
        }
        bar.append("§7"); // 減った分はグレー
        for (int i = activeBars; i < maxBars; i++) {
            bar.append("|");
        }
        return bar.toString();
    }

    // 2. プレイヤーの頭上とTabリストの表示を更新するメソッド
    public static void updatePlayerHPBar(Player target, double currentHp, double maxHp) {
        String bar = generateHpBar(currentHp, maxHp);
        String separator = "  "; // 名前の後ろにスペース2つ空けてバーを表示

        // ==========================================
        // ★変更: TabリストにはHPバーを出さず、名前だけをそのまま表示するようにする
        // ==========================================
        target.setPlayerListName(target.getName());

        // 頭上の名前更新 (全員のスコアボードにTeamを作って共有する安全な方法)
        for (Player p : Bukkit.getOnlinePlayers()) {
            Scoreboard board = p.getScoreboard();
            if (board != null) {
                Team team = board.getTeam(target.getName());
                if (team == null) {
                    team = board.registerNewTeam(target.getName());
                }
                if (!team.hasEntry(target.getName())) {
                    team.addEntry(target.getName());
                }
                // 名前の後ろ(Suffix)にバーを追加（頭上のみ）
                team.setSuffix(separator + bar);
            }
        }
    }
}
