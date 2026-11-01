package sac.plugin.info.SACplugin.party;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import sac.plugin.info.SACplugin.Player.PlayerData;
import sac.plugin.info.SACplugin.SACplugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class FriendManager {
    private final SACplugin plugin;
    private final Map<UUID, UUID> pendingRequests = new HashMap<>();

    public FriendManager(SACplugin plugin) {
        this.plugin = plugin;
    }

    public void sendRequest(Player sender, Player target) {
        UUID senderId = sender.getUniqueId();
        UUID targetId = target.getUniqueId();

        PlayerData senderData = plugin.getPlayerManager().getStats(sender);
        if (senderData != null && senderData.friends.contains(targetId)) {
            sender.sendMessage("§b[System] §r§c既にフレンドです");
            return;
        }

        pendingRequests.put(targetId, senderId);
        sender.sendMessage("§a" + target.getName() + " にフレンド申請を送信しました");

        target.sendMessage("§b=============================");
        target.sendMessage("§e" + sender.getName() + " §aからフレンド申請が届きました");
        target.sendMessage("§b[System] §r§f承認しますか？");

        TextComponent acceptBtn = new TextComponent("§a[はい] ");
        acceptBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/friendaccept " + sender.getName()));

        TextComponent denyBtn = new TextComponent("§c[いいえ]");
        denyBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/frienddeny " + sender.getName()));

        target.spigot().sendMessage(acceptBtn, denyBtn);
        target.sendMessage("§b=============================");
    }

    public void acceptRequest(Player target, Player sender) {
        UUID targetId = target.getUniqueId();
        UUID senderId = sender.getUniqueId();

        PlayerData targetData = plugin.getPlayerManager().getStats(target);
        PlayerData senderData = plugin.getPlayerManager().getStats(sender);

        if (targetData != null && senderData != null) {
            if (!targetData.friends.contains(senderId)) targetData.friends.add(senderId);
            if (!senderData.friends.contains(targetId)) senderData.friends.add(targetId);

            plugin.getPlayerManager().savePlayerData(target, targetData);
            plugin.getPlayerManager().savePlayerData(sender, senderData);

            target.sendMessage("§a" + sender.getName() + " とフレンドになりました");
            sender.sendMessage("§a" + target.getName() + " とフレンドになりました");
        }
        pendingRequests.remove(targetId);
    }

    public void denyRequest(Player target, Player sender) {
        target.sendMessage("§c" + sender.getName() + " のフレンド申請を断りました");
        sender.sendMessage("§c" + target.getName() + " にフレンド申請を断られました");
        pendingRequests.remove(target.getUniqueId());
    }

    public boolean hasPendingRequestFrom(Player target, Player sender) {
        return pendingRequests.containsKey(target.getUniqueId()) && pendingRequests.get(target.getUniqueId()).equals(sender.getUniqueId());
    }

    public void removeFriend(Player player, UUID targetId, String targetName) {
        PlayerData data = plugin.getPlayerManager().getStats(player);
        if (data != null) {
            data.friends.remove(targetId);
            plugin.getPlayerManager().savePlayerData(player, data);
            player.sendMessage("§c" + targetName + " をフレンドから削除しました");

            // 相手のデータからも消去 (オフラインでもOK)
            plugin.getPlayerManager().removeFriendFromOfflinePlayer(targetId, player.getUniqueId());

            Player target = Bukkit.getPlayer(targetId);
            if (target != null) {
                target.sendMessage("§c" + player.getName() + " にフレンドを解除されました");
            }
        }
    }
}
