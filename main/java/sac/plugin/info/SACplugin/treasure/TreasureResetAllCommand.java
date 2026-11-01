package sac.plugin.info.SACplugin.treasure;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import sac.plugin.info.SACplugin.SACplugin;

/**
 * ★追加: サーバー内の全宝箱の「開封済みプレイヤーUUID」記録を一括リセットするコマンド。
 * 個別リセット(死亡時のresetOpenedByPlayer)とは異なり、宝箱側の記録を全プレイヤー分クリアする。
 * これにより、全プレイヤーが全ての宝箱を再度開封できるようになる。
 */
public class TreasureResetAllCommand implements CommandExecutor {
    private final SACplugin plugin;

    public TreasureResetAllCommand(SACplugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§b[System] §r§cプレイヤーのみ実行できます。");
            return true;
        }
        Player player = (Player) sender;
        if (!player.isOp()) {
            player.sendMessage("§b[System] §r§cPERMISSION DENIED");
            return true;
        }

        // ★ 誤爆防止のため確認引数を必須にする
        if (args.length < 1 || !args[0].equalsIgnoreCase("confirm")) {
            player.sendMessage("§b[System] §r§c本当に全ての宝箱の開封履歴をリセットしますか？");
            player.sendMessage("§b[System] §r§c実行するには §e/treasureresetall confirm §cと入力してください。");
            return true;
        }

        int count = plugin.getTreasureManager().resetAllOpened();
        player.sendMessage("§a[System] 全宝箱の開封履歴をリセットしました。§7(対象: " + count + "件)");
        return true;
    }
}
