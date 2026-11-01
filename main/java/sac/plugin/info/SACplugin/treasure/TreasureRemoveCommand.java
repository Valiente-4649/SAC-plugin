package sac.plugin.info.SACplugin.treasure;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import sac.plugin.info.SACplugin.SACplugin;

import java.util.HashSet;
import java.util.Set;

public class TreasureRemoveCommand implements CommandExecutor {
    private final SACplugin plugin;

    public TreasureRemoveCommand(SACplugin plugin) {
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

        // 近くにある宝箱関連エンティティ(Interaction/BlockDisplay/TextDisplay)からIDを集める
        Set<String> nearbyIds = new HashSet<>();
        for (Entity e : player.getNearbyEntities(5.0, 5.0, 5.0)) {
            for (String tag : e.getScoreboardTags()) {
                if (tag.startsWith("sao_treasure_") && !tag.equals("sao_treasure_chest") && !tag.equals("sao_treasure_label")) {
                    nearbyIds.add(tag.replace("sao_treasure_", ""));
                }
            }
        }

        if (nearbyIds.isEmpty()) {
            player.sendMessage("§b[System] §r§c近くに削除できる宝箱が見つかりませんでした。");
            return true;
        }

        int removedCount = 0;
        for (String id : nearbyIds) {
            TreasureData data = plugin.getTreasureManager().getTreasure(id);
            String treasureName = data != null ? data.name : id;

            // 関連エンティティを全て削除
            for (org.bukkit.World world : org.bukkit.Bukkit.getWorlds()) {
                for (Entity e : world.getEntities()) {
                    if (e.getScoreboardTags().contains("sao_treasure_" + id)) {
                        e.remove();
                    }
                }
            }

            // データ・ファイルを削除
            plugin.getTreasureManager().removeTreasure(id);
            removedCount++;
            player.sendMessage("§a[System] 宝箱「" + treasureName + "」を削除しました。");
        }

        if (removedCount > 1) {
            player.sendMessage("§a[System] 合計 " + removedCount + " 個の宝箱を削除しました。");
        }
        return true;
    }
}
