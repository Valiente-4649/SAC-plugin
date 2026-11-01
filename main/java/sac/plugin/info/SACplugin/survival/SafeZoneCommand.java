package sac.plugin.info.SACplugin.survival;

import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import sac.plugin.info.SACplugin.SACplugin;

/**
 * ★新規: OP限定。その場に「セーフゾーン」(HP自動回復地点)を設置するコマンド。
 * 街の転移門周辺と同じく、範囲内にいるプレイヤーは毎秒maxHPの5%回復する。
 * 使用法: /safezone <名前> [半径(省略時20)]
 */
public class SafeZoneCommand implements CommandExecutor {
    private final SACplugin plugin;

    public SafeZoneCommand(SACplugin plugin) {
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
        if (args.length < 1) {
            player.sendMessage("§b[System] §r§c使用法: /safezone <名前> [半径(省略時20)]");
            return true;
        }

        // 最後の引数が数値なら「半径」として扱い、それ以外を名前として結合する
        double radius = 20.0;
        String name;
        try {
            radius = Double.parseDouble(args[args.length - 1]);
            if (args.length < 2) {
                player.sendMessage("§b[System] §r§c使用法: /safezone <名前> [半径(省略時20)]");
                return true;
            }
            name = String.join(" ", java.util.Arrays.copyOf(args, args.length - 1));
        } catch (NumberFormatException e) {
            name = String.join(" ", args);
        }
        if (name.trim().isEmpty()) name = "セーフゾーン";
        if (radius <= 0) radius = 20.0;

        Location loc = player.getLocation();
        String id = plugin.getSafeZoneManager().createZone(name, loc, radius);

        // ★カスタムテキスト(浮遊文字)を設置して視覚的に分かるようにする
        ArmorStand stand = (ArmorStand) loc.getWorld().spawnEntity(loc.clone().add(0, 1.0, 0), EntityType.ARMOR_STAND);
        stand.setVisible(false);
        stand.setGravity(false);
        stand.setBasePlate(false);
        stand.setMarker(true);
        stand.setCustomName("§b[セーフゾーン] §f" + name);
        stand.setCustomNameVisible(true);
        stand.addScoreboardTag("sao_safezone_marker_" + id);

        player.sendMessage("§a[System] セーフゾーン「" + name + "」を設置しました！ §7(半径" + (int) radius + "ブロック / ID: " + id + ")");
        player.sendMessage("§b[System] §r§7この範囲内にいるプレイヤーは毎秒maxHPの5%回復します。");
        return true;
    }
}
