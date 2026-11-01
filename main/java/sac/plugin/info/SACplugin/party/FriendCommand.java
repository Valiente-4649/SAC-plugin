package sac.plugin.info.SACplugin.party;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import sac.plugin.info.SACplugin.SACplugin;

public class FriendCommand implements CommandExecutor {
    private final SACplugin plugin;

    public FriendCommand(SACplugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) return true;
        Player player = (Player) sender;

        if (args.length < 1) return true;
        Player target = Bukkit.getPlayer(args[0]);

        if (target == null) {
            player.sendMessage("§b[System] §r§cそのプレイヤーはオフラインです");
            return true;
        }

        if (!plugin.getFriendManager().hasPendingRequestFrom(player, target)) {
            player.sendMessage("§b[System] §r§c申請の有効期限が切れているか、申請されていません");
            return true;
        }

        if (command.getName().equalsIgnoreCase("friendaccept")) {
            plugin.getFriendManager().acceptRequest(player, target);
        } else if (command.getName().equalsIgnoreCase("frienddeny")) {
            plugin.getFriendManager().denyRequest(player, target);
        }
        return true;
    }
}
