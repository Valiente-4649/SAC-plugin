package sac.plugin.info.SACplugin.boss;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import sac.plugin.info.SACplugin.SACplugin;
import sac.plugin.info.SACplugin.util.Reloadable;

import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Type;
import java.util.*;

public class BossManager implements Reloadable {
    private final SACplugin plugin;
    private final Map<String, BossData> bossDataMap = new HashMap<>();
    private final Map<UUID, Integer> bossEmptyTimer = new HashMap<>();

    public BossManager(SACplugin plugin) {
        this.plugin = plugin;
        loadBosses();
        startBossMonitor();
    }

    public void loadBosses() {
        File file = new File(plugin.getDataFolder(), "boss.json");
        if (!file.exists()) plugin.saveResource("boss.json", false);

        try (FileReader reader = new FileReader(file)) {
            Type type = new TypeToken<Map<String, BossData>>(){}.getType();
            Map<String, BossData> loaded = new Gson().fromJson(reader, type);
            if (loaded != null) {
                bossDataMap.clear();
                bossDataMap.putAll(loaded);
                plugin.getLogger().info("[SAO] ボス部屋データを " + bossDataMap.size() + " 件読み込みました。");
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    @Override
    public void reload() {
        loadBosses();
    }

    public BossData getBossData(String bossId) {
        return bossDataMap.get(bossId);
    }

    // 部屋の中にプレイヤーがいるか探す
    public List<Player> getPlayersInBossRoom(String bossId) {
        List<Player> players = new ArrayList<>();
        BossData data = getBossData(bossId);
        if (data == null) return players;

        World world = Bukkit.getWorld(data.world);
        if (world == null) return players;

        double minX = Math.min(data.pos1X, data.pos2X);
        double maxX = Math.max(data.pos1X, data.pos2X);
        double minY = Math.min(data.pos1Y, data.pos2Y);
        double maxY = Math.max(data.pos1Y, data.pos2Y);
        double minZ = Math.min(data.pos1Z, data.pos2Z);
        double maxZ = Math.max(data.pos1Z, data.pos2Z);

        for (Player p : world.getPlayers()) {
            Location loc = p.getLocation();
            if (loc.getX() >= minX && loc.getX() <= maxX &&
                    loc.getY() >= minY && loc.getY() <= maxY &&
                    loc.getZ() >= minZ && loc.getZ() <= maxZ) {
                players.add(p);
            }
        }
        return players;
    }

    // 誰もいなくなったら10秒でデスポーンさせる監視タスク
    private void startBossMonitor() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (World world : Bukkit.getWorlds()) {
                    for (LivingEntity entity : world.getLivingEntities()) {
                        for (String tag : entity.getScoreboardTags()) {
                            if (tag.startsWith("sao_mob_")) {
                                String mobId = tag.replace("sao_mob_", "");
                                BossData bData = getBossData(mobId);
                                if (bData != null) {
                                    // 部屋に人がいない場合
                                    if (getPlayersInBossRoom(mobId).isEmpty()) {
                                        int count = bossEmptyTimer.getOrDefault(entity.getUniqueId(), 0);
                                        count++;
                                        if (count >= 10) { // 10秒経過
                                            // ★ entity.remove() は EntityDeathEvent を発火しないので連鎖スポーンは起きない
                                            // ★ ただし連鎖ボス(Nato/Baran)がデスポーンした場合は
                                            //    SAOMobListener の onMobDeath は発火しないため onDeath も呼ばれない → 安全
                                            // ★追加: EntityDeathEventが発火しないため、レイドのダメージ記録を
                                            // ここで明示的にクリアしないとメモリリークするので忘れずに消す。
                                            plugin.getRaidManager().clearBossDamage(entity.getUniqueId());
                                            if (plugin.getBossBarManager() != null) {
                                                plugin.getBossBarManager().removeBossBar(entity.getUniqueId());
                                            }
                                            entity.remove();
                                            bossEmptyTimer.remove(entity.getUniqueId());
                                            Bukkit.getLogger().info("[SAO] プレイヤーがいないため、ボス(" + mobId + ")をデスポーンしました。");
                                        } else {
                                            bossEmptyTimer.put(entity.getUniqueId(), count);
                                        }
                                    } else {
                                        // 人がいればリセット
                                        bossEmptyTimer.remove(entity.getUniqueId());
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 20L); // 1秒ごとにチェック
    }

    // 討伐成功時の20秒カウントダウンとTP処理
    public void handleBossDefeat(String bossId) {
        BossData data = getBossData(bossId);
        if (data == null) return;

        List<Player> players = getPlayersInBossRoom(bossId);
        if (players.isEmpty()) return;

        for (Player p : players) {
            p.sendMessage("§a[System] ボス討伐成功！ 20秒後に転移します...");
        }

        new BukkitRunnable() {
            int time = 20;
            @Override
            public void run() {
                if (time <= 0) {
                    Location outLoc = new Location(Bukkit.getWorld(data.world), data.tpOutX, data.tpOutY, data.tpOutZ, data.tpOutYaw, data.tpOutPitch);
                    for (Player p : players) {
                        if (p.isOnline()) {
                            p.teleport(outLoc);
                            p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
                            p.sendMessage("§b[System] 転移しました。");
                        }
                    }
                    this.cancel();
                    return;
                }

                if (time == 10 || time <= 5) {
                    for (Player p : players) {
                        if (p.isOnline()) {
                            p.sendMessage("§a[System] 転移まであと " + time + " 秒");
                            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1.0f, 1.0f);
                        }
                    }
                }
                time--;
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }
    public java.util.Collection<BossData> getAllBossData() {
        return bossDataMap.values();
    }
}