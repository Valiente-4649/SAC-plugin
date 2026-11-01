package sac.plugin.info.SACplugin.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import sac.plugin.info.SACplugin.SACplugin;
import sac.plugin.info.SACplugin.util.Reloadable;

/**
 * /sacreload
 * SACplugin配下のJSON/YML設定ファイルを、サーバーを再起動せず、プレイヤーをキックせず、
 * モブAIや他システムも止めずに再読み込みする。
 *
 * ★このコマンド自体は個々のマネージャーを直接呼び出さない。
 * SACplugin#getReloadables() に登録されている Reloadable を全部回すだけなので、
 * 新しいJSON/YMLを持つマネージャーが増えても、このファイルは一切変更不要。
 * 新しいマネージャー側で implements Reloadable し、SACplugin#onEnable() 内で
 * reloadables.add(...) するだけで自動的にこのコマンドの対象になる。
 *
 * 注意: これは「データ(JSON/YML)」の再読み込みのみ。
 * .javaのロジックを変更した場合(jarの中身そのものを差し替えたい場合)は、
 * このコマンドでは反映されない。その場合はPlugMan等でプラグイン単体をアンロード/リロードすること。
 */
public class SACReloadCommand implements CommandExecutor {
    private final SACplugin plugin;

    public SACReloadCommand(SACplugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("sao.admin")) {
            sender.sendMessage("§b[System] §r§cPERMISSION DENIED。");
            return true;
        }

        StringBuilder result = new StringBuilder();
        int successCount = 0;
        int failCount = 0;

        for (Reloadable reloadable : plugin.getReloadables()) {
            String name = reloadable.getReloadName();
            try {
                reloadable.reload();
                result.append("§a✔ ").append(name).append("\n");
                successCount++;
            } catch (Exception e) {
                result.append("§c✘ ").append(name).append(" の再読み込みに失敗: ").append(e.getMessage()).append("\n");
                failCount++;
                e.printStackTrace();
            }
        }

        sender.sendMessage("§b[SACReload] §f設定データの再読み込みが完了しました。(成功:" + successCount + " / 失敗:" + failCount + ")");
        sender.sendMessage(result.toString());
        if (failCount == 0) {
            sender.sendMessage("§b[System] §r§7プレイヤーのログアウトやモブAIの停止は一切発生していません。");
        }

        return true;
    }
}
