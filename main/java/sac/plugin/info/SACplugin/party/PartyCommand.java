package sac.plugin.info.SACplugin.party;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import sac.plugin.info.SACplugin.SACplugin;

public class PartyCommand implements CommandExecutor {
    private final SACplugin plugin;

    public PartyCommand(SACplugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) return true;
        Player player = (Player) sender;

        if (args.length < 1) return true;
        Player inviter = Bukkit.getPlayer(args[0]);

        if (inviter == null) {
            player.sendMessage("§b[System] §r§cそのプレイヤーはオフラインです");
            return true;
        }

        // 招待が来ているか確認
        if (!plugin.getPartyManager().hasPendingInviteFrom(player, inviter)) {
            player.sendMessage("§b[System] §r§c招待の有効期限が切れているか、招待されていません");
            return true;
        }

        if (command.getName().equalsIgnoreCase("partyaccept")) {
            // 参加処理
            plugin.getPartyManager().joinParty(player, inviter);
        } else if (command.getName().equalsIgnoreCase("partydeny")) {
            // 拒否処理
            plugin.getPartyManager().denyInvite(player, inviter);
        }

        return true;
    }
}
