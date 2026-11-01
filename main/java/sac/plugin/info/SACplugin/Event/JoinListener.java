package sac.plugin.info.SACplugin.Event;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import sac.plugin.info.SACplugin.Player.PlayerData;
import sac.plugin.info.SACplugin.SACplugin;

public class JoinListener implements Listener {
    private final SACplugin plugin;

    public JoinListener(SACplugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // 1. データを読み込む
        try {
            plugin.getPlayerManager().setupPlayer(player);
        } catch (Exception e) {
            plugin.getLogger().severe("[SAO] " + player.getName() + " のsetupPlayerで例外が発生しました！");
            e.printStackTrace();
        }
        PlayerData data = plugin.getPlayerManager().getStats(player);
        if (data == null) {
            plugin.getLogger().warning("[SAO] " + player.getName() + " のPlayerDataがnullのため、ステータス・スコアボード処理をスキップします。");
            return;
        }

        // オフライン休息からの回復はログイン時のロード後に適用。
        if (plugin.getVitalityManager() != null) plugin.getVitalityManager().onDataLoaded(player, data);

        // ==========================================
        // ★修正: バニラのfoodLevelを固定値にするのではなく、実際の満腹ゲージ(hunger)%に
        // 合わせて同期する(バニラバーは見た目のミラー、SurvivalManagerが主導)。
        // ==========================================
        plugin.getPlayerManager().syncFoodLevelBar(player, data);

        // sac.plugin.info.SACplugin.UI.SAOHPDisplay.updatePlayerHPBar(player, data.currentHP, data.maxHP);

        if (!player.hasPlayedBefore()) {
            // 完全な初ログイン時：ナーヴギアのみを渡す
            player.getInventory().clear();
            player.getInventory().addItem(sac.plugin.info.SACplugin.Tutorial.TutorialManager.getNerveGear());
            player.sendMessage("§b[System] §r§bナーヴギアを手に入れた！　使用方法はインベントリから！");
        } else {
            // 2回目以降のログイン：スキルメニューがなければ渡す
            giveSkillMenu(player);
        }

        // 2. 最大HPの見た目を「ハート10個」に固定
        player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH).setBaseValue(20.0);

        // 3. 現在のHPに合わせてハートの量をセット (割合計算)
        double visualHealth = (data.currentHP / data.maxHP) * 20.0;
        // 1.0未満だと死んでしまうので最低1.0(ハート半分)を維持
        player.setHealth(Math.min(20.0, Math.max(1.0, visualHealth)));

        // ==========================================
        // ★追加: 過去のHP表示(Tabリストと頭上)を綺麗に消去する「消しゴム」処理
        // ==========================================
        player.setPlayerListName(player.getName()); // Tabリストを純粋な名前に戻す
        for (Player p : Bukkit.getOnlinePlayers()) {
            org.bukkit.scoreboard.Scoreboard board = p.getScoreboard();
            if (board != null) {
                org.bukkit.scoreboard.Team team = board.getTeam(player.getName());
                if (team != null) {
                    team.setSuffix(""); // 名前の後ろにくっついていたバーを空っぽにする
                }
            }
        }

        // SAC Guide: 既存ログイン処理が終わってから案内を1回表示する。
        // 新規参加者はチュートリアルを優先し、案内を表示しない。
        if (player.hasPlayedBefore()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    SACGuide.sendGuide(plugin, player);
                }
            }, 40L);
        }

        // 4. スコアボード更新
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            plugin.getSaoScoreboard().updateScoreboard(player, data);
        }, 1L);
    }
    private void giveSkillMenu(Player player) {
        String menuName = "§b《Menu》";

        // すでに持っているか、またはナーヴギアを持っているか確認
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == Material.PRISMARINE_SHARD) {
                if (item.hasItemMeta()) {
                    String displayName = item.getItemMeta().getDisplayName();

                    // 既にメニューを持っている場合は配布しない
                    if (displayName.equals(menuName)) {
                        return;
                    }
                    // ★追加: ナーヴギアを持っている(チュートリアル前)場合も配布しない
                    if (displayName.equals("§8NerveGear")) {
                        return;
                    }
                }
            }
        }

        // アイテム作成
        ItemStack skillMenu = new ItemStack(Material.PRISMARINE_SHARD);
        ItemMeta meta = skillMenu.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(menuName);
            // ロア（説明文）を追加してSAOらしさを出す
            java.util.List<String> lore = new java.util.ArrayList<>();
            lore.add("§7右クリックでメニューを開く");
            meta.setLore(lore);
            skillMenu.setItemMeta(meta);
        }

        // インベントリの空きスロット（例えばスロット8番）にセット
        player.getInventory().addItem(skillMenu);
    }
}