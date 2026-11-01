package sac.plugin.info.SACplugin.vote;

import com.vexsoftware.votifier.model.Vote;
import com.vexsoftware.votifier.model.VotifierEvent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import sac.plugin.info.SACplugin.Player.PlayerData;
import sac.plugin.info.SACplugin.SACplugin;

/**
 * ★新規: NuVotifierからの投票通知を受け取り、ドロップ率アップバフを付与する。
 * - オンライン中の投票: その場で即座に付与
 * - オフライン中の投票: VoteManagerに保留登録し、次回ログイン時に自動付与
 * - まだ本日投票していないプレイヤーには、ログイン時に投票案内(クリック可能なURL)を表示する
 */
public class VoteListener implements Listener {
    private final SACplugin plugin;

    public VoteListener(SACplugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onVote(VotifierEvent event) {
        Vote vote = event.getVote();
        String username = vote.getUsername();

        Player player = Bukkit.getPlayerExact(username);
        if (player != null && player.isOnline()) {
            grantVoteReward(player);
        } else {
            plugin.getVoteManager().markPendingVote(username);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        if (plugin.getVoteManager().hasPendingVote(player.getName())) {
            plugin.getVoteManager().clearPendingVote(player.getName());
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    grantVoteReward(player);
                    player.sendMessage("§a[投票報酬] §fオフライン中の投票を検知し、報酬を付与しました！");
                }
            }, 40L);
            return;
        }

        // ★修正: ログイン直後の瞬間はPlayerDataがまだ読み込まれておらずnullになることがあるため、
        // 判定自体を少し遅らせてから行う
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            PlayerData stats = plugin.getPlayerManager().getStats(player);
            String today = plugin.getDailyManager().getLogicalDateString();
            if (stats != null && !today.equals(stats.lastVoteDate)) {
                sendVoteInvite(player);
            }
        }, 20L);
    }

    private void sendVoteInvite(Player player) {
        String url = plugin.getVoteManager().getVoteUrl();
        String siteName = plugin.getVoteManager().getSiteName();
        if (url == null || url.isEmpty()) return;

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            player.sendMessage("§6========================================");
            player.sendMessage("§e[System] §f本日はまだ投票していません！投票すると15分間ドロップ率が§b2倍§fになります！");

            TextComponent message = new TextComponent("§b▶ ここをクリックして「" + siteName + "」へ投票する");
            message.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
            player.spigot().sendMessage(message);

            player.sendMessage("§6========================================");
        }, 60L);
    }

    private void grantVoteReward(Player player) {
        plugin.getVoteManager().applyDropBuff(player);

        PlayerData stats = plugin.getPlayerManager().getStats(player);
        if (stats != null) {
            stats.lastVoteDate = plugin.getDailyManager().getLogicalDateString();
            plugin.getPlayerManager().savePlayerData(player, stats);
        }

        player.sendMessage("§a[投票報酬] §f投票ありがとうございます！15分間ドロップ率が§b2倍§fになりました！");
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.5f);
    }
}