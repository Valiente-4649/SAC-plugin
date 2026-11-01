package sac.plugin.info.SACplugin.Player;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.TextDisplay;
import sac.plugin.info.SACplugin.SACplugin;

import java.io.File;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * ★新規: 黒鉄宮に設置する「死亡回数ランキング」のテキストディスプレイを管理する。
 * 過去1週間以内にログインしたプレイヤーだけを対象に、死亡回数の多い順で表示する。
 * 誰かが死亡するたびに更新される。
 * 設置座標はdeath_board.ymlに保存し、サーバー再起動を跨いでも同じ場所を更新し続けられるようにする。
 */
public class DeathBoardManager {
    private final SACplugin plugin;
    private static final String TAG = "sao_death_board";
    private static final long ONE_WEEK_MILLIS = 7L * 24 * 60 * 60 * 1000L;
    private static final int MAX_ENTRIES = 10;

    public DeathBoardManager(SACplugin plugin) {
        this.plugin = plugin;
    }

    private File configFile() {
        return new File(plugin.getDataFolder(), "death_board.yml");
    }

    /** 指定座標にランキング表示を新規設置する(既にあれば一旦削除してから設置し直す) */
    public void spawnBoard(Location location) {
        removeBoard();

        Location loc = location.clone();
        TextDisplay display = (TextDisplay) loc.getWorld().spawnEntity(loc, EntityType.TEXT_DISPLAY);
        display.setBillboard(Display.Billboard.CENTER);
        display.addScoreboardTag(TAG);
        display.setInterpolationDuration(0);
        display.setTeleportDuration(0);

        FileConfiguration config = new YamlConfiguration();
        config.set("world", loc.getWorld().getName());
        config.set("x", loc.getX());
        config.set("y", loc.getY());
        config.set("z", loc.getZ());
        try {
            config.save(configFile());
        } catch (IOException e) {
            e.printStackTrace();
        }

        updateBoard();
    }

    /** 近くにある既存のランキング表示を削除する(設置場所の変更・撤去用) */
    public void removeBoard() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity e : world.getEntitiesByClass(TextDisplay.class)) {
                if (e.getScoreboardTags().contains(TAG)) {
                    e.remove();
                }
            }
        }
    }

    /**
     * ★プレイヤーが死亡するたびに呼ぶ。設置済みのランキング表示の中身を最新の内容に更新する。
     * 表示自体が存在しない(まだ設置していない)場合は何もしない。
     */
    public void updateBoard() {
        File file = configFile();
        if (!file.exists()) return;

        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        String worldName = config.getString("world");
        World world = worldName != null ? Bukkit.getWorld(worldName) : null;
        if (world == null) return;

        Location loc = new Location(world, config.getDouble("x"), config.getDouble("y"), config.getDouble("z"));

        TextDisplay target = null;
        for (Entity e : world.getEntitiesByClass(TextDisplay.class)) {
            if (e.getScoreboardTags().contains(TAG) && e.getLocation().distanceSquared(loc) < 4.0) {
                target = (TextDisplay) e;
                break;
            }
        }
        if (target == null) return;

        target.setText(buildBoardText());
    }

    private String buildBoardText() {
        long cutoff = System.currentTimeMillis() - ONE_WEEK_MILLIS;

        List<PlayerManager.DeathRecord> records = plugin.getPlayerManager().getAllDeathRecords().stream()
                .filter(r -> r.lastLoginTime >= cutoff)
                .filter(r -> r.deathCount > 0)
                .sorted(Comparator.comparingInt((PlayerManager.DeathRecord r) -> r.deathCount).reversed())
                .limit(MAX_ENTRIES)
                .collect(Collectors.toList());

        StringBuilder sb = new StringBuilder();
        sb.append("§4死亡回数ランキング\n");
        sb.append("§7(直近1週間にログインしたプレイヤーが対象)\n\n");

        if (records.isEmpty()) {
            sb.append("§7まだ記録がありません");
            return sb.toString();
        }

        int rank = 1;
        for (PlayerManager.DeathRecord r : records) {
            String name = Bukkit.getOfflinePlayer(r.uuid).getName();
            if (name == null) name = "???";
            sb.append("§f").append(rank).append(". §e").append(name)
                    .append(" §c").append(r.deathCount).append("回\n");
            rank++;
        }
        return sb.toString();
    }

    // ==========================================
    // ★追加: 設置・削除コマンド: /deathboard spawn / remove
    // ==========================================
    public static class Command implements org.bukkit.command.CommandExecutor {
        private final SACplugin plugin;
        public Command(SACplugin plugin) { this.plugin = plugin; }

        @Override
        public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
            if (!(sender instanceof org.bukkit.entity.Player)) return true;
            org.bukkit.entity.Player player = (org.bukkit.entity.Player) sender;
            if (!player.isOp()) {
                player.sendMessage("§b[System] §r§cPERMISSION DENIED");
                return true;
            }

            if (args.length > 0 && args[0].equalsIgnoreCase("remove")) {
                plugin.getDeathBoardManager().removeBoard();
                player.sendMessage("§a[System] 死亡回数ランキング表示を削除しました。");
                return true;
            }

            plugin.getDeathBoardManager().spawnBoard(player.getLocation());
            player.sendMessage("§a[System] 死亡回数ランキング表示を設置しました！");
            return true;
        }
    }
}
