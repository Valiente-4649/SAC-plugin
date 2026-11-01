package sac.plugin.info.SACplugin.life;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerBedLeaveEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import sac.plugin.info.SACplugin.Player.PlayerData;
import sac.plugin.info.SACplugin.SACplugin;

/** SAC clockに同期した活力・睡眠・NPC夜間休業の管理。メインスレッド専用。 */
public class VitalityManager implements Listener {
    private static final String HIDDEN_TAG = "SAC_NIGHT_HIDDEN";
    private final SACplugin plugin;
    private final java.util.Set<java.util.UUID> resting = new java.util.HashSet<>();
    private int seconds = 0;
    private boolean previousNight;
    private final java.util.Map<java.util.UUID, Integer> lastPenalty = new java.util.HashMap<>();

    // 1日=180分、昼=14ゲーム時間、夜=10ゲーム時間、夜は昼の2倍速。
    // 昼の実時間は 180 * 14/19 分。起きたままで70ポイント減る。
    private static final double DRAIN_PER_SECOND = 70.0 / (180.0 * 60.0 * 14.0 / 19.0);
    // 夜22:00から翌04:00まで=ゲーム6時間、実時間180 * 3/19分。
    private static final double RECOVER_PER_SECOND = 100.0 / (180.0 * 60.0 * 3.0 / 19.0);

    public VitalityManager(SACplugin plugin) { this.plugin = plugin; }

    public static int penaltyTier(PlayerData data) {
        if (data == null) return 0;
        if (data.vitality <= 0.000001) return 2;
        return data.vitality <= 10.0 ? 1 : 0;
    }
    public static int accuracyPenalty(PlayerData data) { return penaltyTier(data) * 10; }
    public static double speedFactor(PlayerData data) { return 1.0 - penaltyTier(data) * 0.10; }
    private boolean isNight() {
        double hour = plugin.getTimeManager().getGameHour();
        return hour >= 18.0 || hour < 4.0;
    }

    public void start() {
        previousNight = isNight();
        // バニラの「一人が寝ると時刻を進める」処理は使わず、SACが全員就寝判定をする。
        for (World world : Bukkit.getWorlds()) world.setGameRule(GameRule.PLAYERS_SLEEPING_PERCENTAGE, 101);
        for (World world : Bukkit.getWorlds()) {
            for (Villager villager : world.getEntitiesByClass(Villager.class)) updateNpc(villager, previousNight);
        }
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    private void tick() {
        boolean night = isNight();
        seconds++;
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerData data = plugin.getPlayerManager().getStats(player);
            if (data == null) continue;
            int oldTier = penaltyTier(data);
            if (night && player.isSleeping()) data.vitality = Math.min(100, data.vitality + RECOVER_PER_SECOND);
            else if (!night) data.vitality = Math.max(0, data.vitality - DRAIN_PER_SECOND);
            int tier = penaltyTier(data);
            if (oldTier != tier || !lastPenalty.containsKey(player.getUniqueId())) {
                lastPenalty.put(player.getUniqueId(), tier);
                plugin.getAgiListener().updateMovementSpeed(player);
                if (tier > oldTier && tier > 0) player.sendMessage("§b[System] §f活力低下: 命中率と移動速度が低下しています。");
            }
        }
        if (night && !Bukkit.getOnlinePlayers().isEmpty()
                && Bukkit.getOnlinePlayers().stream().allMatch(Player::isSleeping)) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                PlayerData data = plugin.getPlayerManager().getStats(player);
                if (data != null) {
                    data.vitality = 100.0;
                    lastPenalty.put(player.getUniqueId(), 0);
                    plugin.getAgiListener().updateMovementSpeed(player);
                    player.sendMessage("§b[System] §f活力が全回復しました。");
                }
            }
            plugin.getTimeManager().setGameHour(4.0);
            night = false;
        }
        if (night != previousNight || seconds % 30 == 0) {
            // 起動時の未ロードchunkはChunkLoadEventで、ロード済みは巡回で追随。
            for (World world : Bukkit.getWorlds()) {
                for (Villager npc : world.getEntitiesByClass(Villager.class)) updateNpc(npc, night);
            }
            previousNight = night;
        }
        if (seconds % 10 == 0) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                PlayerData data = plugin.getPlayerManager().getStats(player);
                if (data != null) plugin.getSaoScoreboard().updateScoreboard(player, data);
            }
        }
        if (seconds % 60 == 0) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                PlayerData data = plugin.getPlayerManager().getStats(player);
                if (data != null) plugin.getPlayerManager().savePlayerData(player, data);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBedEnter(PlayerBedEnterEvent event) {
        if (!isNight()) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§b[System] §f昼間は休めません。");
        } else {
            event.setUseBed(org.bukkit.event.Event.Result.ALLOW);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (event.getPlayer().isOnline() && event.getPlayer().isSleeping())
                    resting.add(event.getPlayer().getUniqueId());
            }, 1L);
        }
    }

    @EventHandler
    public void onBedLeave(PlayerBedLeaveEvent event) {
        resting.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        boolean night = isNight();
        for (Entity entity : event.getChunk().getEntities()) {
            if (entity instanceof Villager villager) updateNpc(villager, night);
        }
    }

    public boolean isManagedNpc(Entity entity) {
        if (!(entity instanceof Villager)) return false;
        for (String tag : entity.getScoreboardTags()) {
            if (tag.startsWith("QUEST_NPC_") || tag.startsWith("DAILY_NPC_")
                    || tag.startsWith("sao_shop_") || tag.equals("sao_appraisal_npc")) return true;
        }
        return false;
    }
    private void updateNpc(Villager npc, boolean night) {
        if (!isManagedNpc(npc)) return;
        if (night) {
            if (!npc.getScoreboardTags().contains(HIDDEN_TAG)) {
                if (!npc.hasPotionEffect(PotionEffectType.INVISIBILITY)) {
                    npc.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 0, false, false));
                    npc.addScoreboardTag(HIDDEN_TAG);
                }
            }
        } else if (npc.getScoreboardTags().contains(HIDDEN_TAG)) {
            npc.removePotionEffect(PotionEffectType.INVISIBILITY);
            npc.removeScoreboardTag(HIDDEN_TAG);
        }
    }
    @EventHandler(priority = EventPriority.LOWEST)
    public void onNightNpcClick(PlayerInteractEntityEvent event) {
        if (isNight() && isManagedNpc(event.getRightClicked())) event.setCancelled(true);
    }
    public boolean isNpcClosed(Entity entity) { return isNight() && isManagedNpc(entity); }

    public void onDataLoaded(Player player, PlayerData data) {
        if (data.restingOffline && data.vitalityLogoutAt > 0) {
            double elapsedSeconds = Math.max(0, (System.currentTimeMillis() - data.vitalityLogoutAt) / 1000.0);
            data.vitality = Math.min(100.0, data.vitality + elapsedSeconds * RECOVER_PER_SECOND);
        }
        data.restingOffline = false;
        data.vitalityLogoutAt = 0;
        lastPenalty.remove(player.getUniqueId());
        // JoinListenerの各種ステータス同期完了後、速度を再適用
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) plugin.getAgiListener().updateMovementSpeed(player);
        }, 5L);
    }
    public void onLogout(Player player, PlayerData data) {
        data.restingOffline = isNight() && (player.isSleeping() || resting.contains(player.getUniqueId()));
        data.vitalityLogoutAt = data.restingOffline ? System.currentTimeMillis() : 0L;
        lastPenalty.remove(player.getUniqueId());
        resting.remove(player.getUniqueId());
    }
}
