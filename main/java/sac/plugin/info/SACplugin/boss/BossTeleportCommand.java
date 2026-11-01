package sac.plugin.info.SACplugin.boss;

import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import sac.plugin.info.SACplugin.SACplugin;

public class BossTeleportCommand implements CommandExecutor {
    private final SACplugin plugin;

    public BossTeleportCommand(SACplugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) return true;
        Player player = (Player) sender;

        if (!player.isOp()) {
            player.sendMessage("§b[System] §r§cPERMISSION DENIED");
            return true;
        }

        if (args.length < 1) {
            player.sendMessage("§c使用方法: /bosstp <ボスID> (例: illfang_the_kobold_lord)");
            return true;
        }
        String bossId = args[0];

        Location loc = player.getLocation();

        spawnHologramText(loc.clone().add(0, 2.7, 0), "§cーフロアボスに挑むー", bossId);
        spawnHologramText(loc.clone().add(0, 2.4, 0), "§e§n≫右クリックでレイド参加≪", bossId);
        spawnHologramText(loc.clone().add(0, 2.1, 0), "※ボス部屋からの離脱は転移結晶でのみ可能です。", bossId);
        spawnHologramText(loc.clone().add(0, 1.5, 0), "開始時間になると周囲のレイドメンバーが", bossId);
        spawnHologramText(loc.clone().add(0, 1.2, 0), "ボス部屋へ全員テレポートされます。", bossId);

        player.sendMessage("§a[System] ボス部屋(" + bossId + ")の入り口を設置しました！");
        return true;
    }

    private void spawnHologramText(Location loc, String text, String bossId) {
        ArmorStand stand = (ArmorStand) loc.getWorld().spawnEntity(loc, EntityType.ARMOR_STAND);
        stand.setVisible(false);
        stand.setGravity(false);
        stand.setBasePlate(false);
        stand.setMarker(true);
        stand.setCustomName(text);
        stand.setCustomNameVisible(true);
        stand.addScoreboardTag("sao_boss_tp_" + bossId); // ★ボスIDを含めたタグにする
    }
}
