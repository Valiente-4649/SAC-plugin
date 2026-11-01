package sac.plugin.info.SACplugin.survival;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import sac.plugin.info.SACplugin.SACplugin;

import java.util.HashSet;
import java.util.Set;

/**
 * ★新規: OP限定。近くのセーフゾーン(浮遊テキスト)を削除するコマンド。
 */
public class SafeZoneRemoveCommand implements CommandExecutor {
    private final SACplugin plugin;

    public SafeZoneRemoveCommand(SACplugin plugin) {
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

        Set<String> nearbyIds = new HashSet<>();
        for (Entity e : player.getNearbyEntities(5.0, 5.0, 5.0)) {
            for (String tag : e.getScoreboardTags()) {
                if (tag.startsWith("sao_safezone_marker_")) {
                    nearbyIds.add(tag.replace("sao_safezone_marker_", ""));
                }
            }
        }

        if (nearbyIds.isEmpty()) {
            player.sendMessage("§b[System] §r§c近くに削除できるセーフゾーンが見つかりませんでした。");
            return true;
        }

        for (String id : nearbyIds) {
            for (World world : Bukkit.getWorlds()) {
                for (Entity e : world.getEntities()) {
                    if (e.getScoreboardTags().contains("sao_safezone_marker_" + id)) {
                        e.remove();
                    }
                }
            }
            plugin.getSafeZoneManager().removeZone(id);
            player.sendMessage("§a[System] セーフゾーンを削除しました。 §7(ID: " + id + ")");
        }
        return true;
    }
}