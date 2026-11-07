package sac.plugin.info.SACplugin.life;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import sac.plugin.info.SACplugin.Player.PlayerData;
import sac.plugin.info.SACplugin.SACplugin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** SAC時間に同期した活力・独自睡眠・NPC夜間休業の管理。メインスレッド専用。 */
public class VitalityManager implements Listener {
    private static final String HIDDEN_TAG = "SAC_NIGHT_HIDDEN";
    private final SACplugin plugin;
    private final Set<UUID> resting = new HashSet<>();
    private final Map<UUID, Location> sleepAnchors = new HashMap<>();
    private final Map<UUID, Location> sleepOrigins = new HashMap<>();
    private final Map<UUID, GameMode> previousGameModes = new HashMap<>();
    private int seconds = 0;
    private boolean previousNight;
    private final Map<UUID, Integer> lastPenalty = new HashMap<>();

    // SACの1日=現実3時間。昼14ゲーム時間、夜10ゲーム時間、夜は昼の2倍速。
    // 昼14hは実時間約132.63分。昼間起き続けると活力70ポイント消費。
    private static final double DRAIN_PER_SECOND = 70.0 / (180.0 * 60.0 * 14.0 / 19.0);
    // 22:00から翌04:00までの夜6ゲーム時間は実時間約28.42分。0から100まで回復。
    private static final double RECOVER_PER_SECOND = 100.0 / (180.0 * 60.0 * 3.0 / 19.0);

    public VitalityManager(SACplugin plugin) { this.plugin = plugin; }

    public static int penaltyTier(PlayerData data) {
        if (data == null) return 0;
        if (data.vitality <= 0.000001) return 2;
        return data.vitality <= 10.0 ? 1 : 0;
    }
    public static int accuracyPenalty(PlayerData data) { return penaltyTier(data) * 10; }
    public static double speedFactor(PlayerData data) { return 1.0 - penaltyTier(data) * 0.10; }

    public boolean isNight() {
        double hour = plugin.getTimeManager().getGameHour();
        return hour >= 18.0 || hour < 4.0;
    }

    public boolean isResting(Player player) {
        return player != null && resting.contains(player.getUniqueId());
    }

    public void start() {
        previousNight = isNight();
        // 独自睡眠で全員就寝判定を行うため、バニラの時間スキップを無効化。
        for (World world : Bukkit.getWorlds()) {
            world.setGameRule(GameRule.PLAYERS_SLEEPING_PERCENTAGE, 101);
            for (Villager villager : world.getEntitiesByClass(Villager.class)) updateNpc(villager, previousNight);
        }
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    private void tick() {
        seconds++;
        boolean night = isNight();

        // 朝になったら、睡眠中の全プレイヤーを強制的に起床させる。
        if (!night && !resting.isEmpty()) {
            for (UUID uuid : new HashSet<>(resting)) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.isOnline()) wakePlayer(player, true);
                else resting.remove(uuid);
            }
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerData data = plugin.getPlayerManager().getStats(player);
            if (data == null) continue;
            int oldTier = penaltyTier(data);
            UUID uuid = player.getUniqueId();
            if (resting.contains(uuid) && night) {
                data.vitality = Math.min(100.0, data.vitality + RECOVER_PER_SECOND);
                player.sendActionBar(net.kyori.adventure.text.Component.text("睡眠中"));
            } else if (!night) {
                data.vitality = Math.max(0.0, data.vitality - DRAIN_PER_SECOND);
            }
            int tier = penaltyTier(data);
            if (oldTier != tier || !lastPenalty.containsKey(uuid)) {
                lastPenalty.put(uuid, tier);
                plugin.getAgiListener().updateMovementSpeed(player);
                if (tier > oldTier && tier > 0) player.sendMessage("§b[System] §f活力低下: 命中率と移動速度が低下しています。");
            }
        }

        // オンライン全員が独自睡眠中なら、活力を即時全回復してSAC時間を朝4時へ。
        if (night && !Bukkit.getOnlinePlayers().isEmpty()
                && Bukkit.getOnlinePlayers().stream().allMatch(this::isResting)) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                PlayerData data = plugin.getPlayerManager().getStats(player);
                if (data != null) {
                    data.vitality = 100.0;
                    lastPenalty.put(player.getUniqueId(), 0);
                    plugin.getAgiListener().updateMovementSpeed(player);
                    player.sendMessage("§b[System] §f活力が全回復しました。");
                    wakePlayer(player, false);
                }
            }
            plugin.getTimeManager().setGameHour(4.0);
            night = false;
        }

        if (night != previousNight || seconds % 30 == 0) {
            for (World world : Bukkit.getWorlds()) {
                for (Villager villager : world.getEntitiesByClass(Villager.class)) updateNpc(villager, night);
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

    /** ベッド右クリックをバニラ睡眠から独自睡眠へ置き換える。 */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onBedInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null
                || !event.getClickedBlock().getType().name().endsWith("_BED")) return;
        Player player = event.getPlayer();
        event.setCancelled(true);

        if (isResting(player)) {
            wakePlayer(player, false);
            return;
        }
        if (!isNight()) {
            player.sendMessage("§b[System] §f昼間はベッドで休めません。");
            return;
        }
        startSleep(player, event.getClickedBlock().getLocation());
    }

    /** 何らかの経路でバニラのベッド睡眠が始まることも防ぐ。 */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onVanillaBedEnter(PlayerBedEnterEvent event) {
        event.setCancelled(true);
    }

    private void startSleep(Player player, Location bedBlock) {
        UUID uuid = player.getUniqueId();
        if (!isNight() || resting.contains(uuid)) return;
        Location origin = player.getLocation().clone();
        Location anchor = new Location(bedBlock.getWorld(), bedBlock.getBlockX() + 0.5,
                bedBlock.getBlockY() + 1.0, bedBlock.getBlockZ() + 0.5,
                origin.getYaw(), origin.getPitch());
        sleepOrigins.put(uuid, origin);
        sleepAnchors.put(uuid, anchor);
        previousGameModes.put(uuid, player.getGameMode());
        resting.add(uuid);
        player.setGameMode(GameMode.SPECTATOR);
        player.teleport(anchor);
        player.sendActionBar(net.kyori.adventure.text.Component.text("睡眠中"));
        player.sendMessage("§b[System] §f睡眠を開始しました。右クリックで起床できます。");
    }

    /** 睡眠中は座標をベッドの1ブロック上に固定する。 */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onSleepingMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (!resting.contains(uuid) || event.getTo() == null) return;
        Location anchor = sleepAnchors.get(uuid);
        if (anchor == null) return;
        Location to = event.getTo();
        if (to.getWorld() != anchor.getWorld() || to.distanceSquared(anchor) > 0.0001) {
            Location fixed = anchor.clone();
            fixed.setYaw(to.getYaw());
            fixed.setPitch(to.getPitch());
            event.setTo(fixed);
        }
    }

    private void wakePlayer(Player player, boolean morningForced) {
        UUID uuid = player.getUniqueId();
        if (!resting.remove(uuid)) return;
        Location origin = sleepOrigins.remove(uuid);
        Location anchor = sleepAnchors.remove(uuid);
        GameMode previous = previousGameModes.remove(uuid);
        if (previous == null || previous == GameMode.SPECTATOR) previous = GameMode.SURVIVAL;
        player.setGameMode(previous);
        Location destination = origin != null ? origin : anchor;
        if (destination != null && destination.getWorld() != null) player.teleport(destination);
        player.sendActionBar(net.kyori.adventure.text.Component.empty());
        if (morningForced) player.sendMessage("§b[System] §f朝になりました。睡眠を終了します。");
        else player.sendMessage("§b[System] §f睡眠を終了しました。");
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

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
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
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) plugin.getAgiListener().updateMovementSpeed(player);
        }, 5L);
    }

    public void onLogout(Player player, PlayerData data) {
        UUID uuid = player.getUniqueId();
        boolean wasResting = resting.contains(uuid);
        data.restingOffline = wasResting && isNight();
        data.vitalityLogoutAt = data.restingOffline ? System.currentTimeMillis() : 0L;
        if (wasResting) {
            Location origin = sleepOrigins.remove(uuid);
            sleepAnchors.remove(uuid);
            GameMode previous = previousGameModes.remove(uuid);
            resting.remove(uuid);
            if (previous != null && previous != GameMode.SPECTATOR) player.setGameMode(previous);
            if (origin != null) player.teleport(origin);
        }
        lastPenalty.remove(uuid);
    }
}
