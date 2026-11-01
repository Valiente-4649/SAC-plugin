package sac.plugin.info.SACplugin.daily;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import sac.plugin.info.SACplugin.Player.PlayerData;
import sac.plugin.info.SACplugin.SACplugin;

import java.io.File;

public class DailyCommand implements CommandExecutor {
    private final SACplugin plugin;

    public DailyCommand(SACplugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("sao.admin")) {
            sender.sendMessage("§b[System] §r§cPERMISSION DENIED。");
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage("§b[System] §r§7使い方: /daily <setnpc|setspot|reload|reset> [floor/プレイヤー名]");
            return true;
        }

        String sub = args[0].toLowerCase();

        // --- リセットと再抽選はコンソールからも使えるようにする ---
        if (sub.equals("reset")) {
            if (args.length < 2) {
                sender.sendMessage("§b[System] §r§c使い方: /daily reset <プレイヤー名>");
                return true;
            }
            resetPlayerDaily(sender, args[1]);
            return true;
        }
        if (sub.equals("reload")) {
            plugin.getDailyManager().rollAll();
            sender.sendMessage("§a[System] デイリー内容を強制再抽選しました。");
            return true;
        }

        // --- 以下は現在地を使うため、プレイヤー限定 ---
        if (!(sender instanceof Player)) {
            sender.sendMessage("§b[System] §r§cこのコマンドはゲーム内でのみ使用できます。");
            return true;
        }
        Player player = (Player) sender;

        switch (sub) {
            case "setnpc": {
                if (args.length < 2) {
                    player.sendMessage("§b[System] §r§c使い方: /daily setnpc <floor>");
                    return true;
                }
                int floor;
                try { floor = Integer.parseInt(args[1]); } catch (NumberFormatException e) {
                    player.sendMessage("§b[System] §r§c階層は数字で指定してください。");
                    return true;
                }

                DailyFloorPoolData pool = plugin.getDailyManager().getPool(floor);
                String npcName = (pool != null && pool.npcName != null && !pool.npcName.isEmpty())
                        ? pool.npcName : ("デイリーおじさん(第" + floor + "層)");

                Villager npc = (Villager) player.getWorld().spawnEntity(player.getLocation(), EntityType.VILLAGER);
                npc.setCustomName(npcName);
                npc.setCustomNameVisible(true);
                npc.setAI(false);
                npc.setInvulnerable(true);
                npc.setSilent(true);
                npc.addScoreboardTag("DAILY_NPC_" + floor);

                player.sendMessage("§a[System] 第" + floor + "層のデイリーおじさんを設置しました。");
                return true;
            }
            case "setspot": {
                if (args.length < 2) {
                    player.sendMessage("§b[System] §r§c使い方: /daily setspot <floor>");
                    return true;
                }
                int floor;
                try { floor = Integer.parseInt(args[1]); } catch (NumberFormatException e) {
                    player.sendMessage("§b[System] §r§c階層は数字で指定してください。");
                    return true;
                }

                int count = plugin.getDailyManager().registerSpot(floor, player.getLocation());
                player.sendMessage("§a[System] 第" + floor + "層の宝箱候補地点として登録しました。(現在" + count + "箇所登録済み)");
                return true;
            }
            default:
                player.sendMessage("§b[System] §r§7使い方: /daily <setnpc|setspot|reload|reset> [floor/プレイヤー名]");
                return true;
        }
    }

    /** 指定プレイヤーの「本日のデイリークリア状況」をリセットする（オンライン・オフライン両対応） */
    private void resetPlayerDaily(CommandSender sender, String targetName) {
        @SuppressWarnings("deprecation")
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);

        if (target.isOnline()) {
            Player targetPlayer = (Player) target;
            PlayerData stats = plugin.getPlayerManager().getStats(targetPlayer);
            if (stats != null) {
                stats.lastDailyClearDate = "";
                plugin.getPlayerManager().savePlayerData(targetPlayer, stats);
                sender.sendMessage("§a[System] " + targetName + " のデイリークリア状況をリセットしました。");
                return;
            }
        }

        // オフライン、またはstatsが読み込まれていない場合はファイルを直接編集する
        File file = new File(plugin.getDataFolder(), "userdata/" + target.getUniqueId() + ".yml");
        if (!file.exists()) {
            sender.sendMessage("§b[System] §r§cそのプレイヤーのデータが見つかりません。プレイヤー名を確認してください。");
            return;
        }
        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        config.set("lastDailyClearDate", "");
        try {
            config.save(file);
            sender.sendMessage("§a[System] " + targetName + " のデイリークリア状況をリセットしました。(オフライン)");
        } catch (Exception e) {
            e.printStackTrace();
            sender.sendMessage("§b[System] §r§cリセット中にエラーが発生しました。コンソールを確認してください。");
        }
    }
}
