package sac.plugin.info.SACplugin.skill;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import sac.plugin.info.SACplugin.Player.PlayerData;
import sac.plugin.info.SACplugin.SACplugin;

public class WeaponSpeedTask extends BukkitRunnable {
    private final SACplugin plugin;

    public WeaponSpeedTask(SACplugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerData stats = plugin.getPlayerManager().getStats(player);

            // データがない場合や死亡中(復活待ち)は速度をいじらない
            if (stats == null || stats.isDead) continue;

            boolean isHeavy = false;

            // ==========================================
            // ★修正: 武器ではなく「選択中のスキル」で判定する！
            // ズル（持ち替えダッシュ）を完全に防止します
            // ==========================================
            if (stats.selectedSkillId != null && !stats.selectedSkillId.isEmpty()) {
                SwordSkillData skillData = plugin.getSwordSkillManager().getSkill(stats.selectedSkillId);

                // 現在セットして構えているスキルが「両手剣」のものなら重くする！
                if (skillData != null && "両手剣".equals(skillData.category)) {
                    isHeavy = true;
                }
            }

            // 速度の適用 (マイクラのデフォルト歩行速度は 0.2f)
            if (isHeavy) {
                // 両手剣スキル構え中は通常の80%の速度 (0.16)
                player.setWalkSpeed(0.17f);
            } else {
                // 他のスキルを構えている、または何もセットしていなければ通常速度 (0.20)
                player.setWalkSpeed(0.20f);
            }
        }
    }
}
