package sac.plugin.info.SACplugin.boss;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import sac.plugin.info.SACplugin.SACplugin;

public class BossRemoveCommand implements CommandExecutor {
    private final SACplugin plugin;

    public BossRemoveCommand(SACplugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) return true;
        Player player = (Player) sender;
        if (!player.isOp()) return true;

        int count = 0;
        for (Entity entity : player.getNearbyEntities(5.0, 5.0, 5.0)) {
            if (entity instanceof ArmorStand) {
                for (String tag : entity.getScoreboardTags()) {
                    // ★ 新仕様のタグ(sao_boss_tp_xxx) と 旧仕様のタグ(sao_boss_tp) を両方とも消去対象にする！
                    if (tag.startsWith("sao_boss_tp_") || tag.equals("sao_boss_tp")) {
                        entity.remove();
                        count++;
                        break;
                    }
                }
            }
        }

        if (count > 0) {
            player.sendMessage("§a[System] ホログラムを " + count + " 行分 削除しました！");
        } else {
            player.sendMessage("§c[System] 近くに削除できるホログラムが見つかりませんでした。");
        }
        return true;
    }
}
