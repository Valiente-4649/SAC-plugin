package sac.plugin.info.SACplugin.achievement;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import sac.plugin.info.SACplugin.SACplugin;

/**
 * /achievement            : 自分の実績メニューを開く
 * /achievement admin ...  : OP用の管理コマンド(ユニーク実績の調整など)
 */
public class AchievementCommand implements CommandExecutor {

    private final SACplugin plugin;

    public AchievementCommand(SACplugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("admin")) {
            if (!(sender instanceof Player)) { sender.sendMessage("プレイヤーのみ実行できます。"); return true; }
            plugin.getAchievementMenu().openOwn((Player) sender);
            return true;
        }
        if (!sender.isOp()) { sender.sendMessage("§c権限がありません。"); return true; }
        AchievementManager m = plugin.getAchievementManager();
        if (args.length >= 2 && args[1].equalsIgnoreCase("ids")) {
            for (AchievementDef d : m.getDefs()) sender.sendMessage("§7" + d.type.label + " §f" + d.id + " §8= " + d.name);
            return true;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("reload")) {
            m.reload();
            sender.sendMessage("§a[実績] 再読み込みしました。");
            return true;
        }
        if (args.length >= 3 && args[1].equalsIgnoreCase("clearunique")) {
            sender.sendMessage(m.adminClearUnique(args[2]) ? "§a保持者を外しました(再び早い者勝ち)。" : "§c保持者がいません/IDが違います。");
            return true;
        }
        if (args.length >= 4 && (args[1].equalsIgnoreCase("grant") || args[1].equalsIgnoreCase("revoke"))) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(args[2]);
            boolean ok = args[1].equalsIgnoreCase("grant") ? m.adminGrant(op.getUniqueId(), args[3]) : m.adminRevoke(op.getUniqueId(), args[3]);
            sender.sendMessage(ok ? "§a実行しました。" : "§c失敗しました(ID/対象を確認してください)。");
            return true;
        }
        sender.sendMessage("§e/achievement admin ids | reload | clearunique <id> | grant <player> <id> | revoke <player> <id>");
        return true;
    }
}
