package sac.plugin.info.SACplugin.boss;

import com.google.gson.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import sac.plugin.info.SACplugin.SACplugin;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class RaidManager {
    private final SACplugin plugin;

    // ★仕様通りの固定時刻。将来的にconfig化したい場合はここをJSON読み込みに差し替えられる。
    private static final int[] RAID_HOURS = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23};
    private static final int REGISTRATION_WINDOW_MINUTES = 30;
    // ホログラム(入り口)から何ブロック以内にいれば「開始時刻に参加」とみなすか
    private static final double ARRIVAL_RADIUS = 10.0;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter SLOT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH");

    // bossId -> 登録済みプレイヤーUUID一覧(直近の受付枠限定)
    private final Map<String, Set<UUID>> registeredPlayers = new HashMap<>();
    // bossId -> その登録がどの枠(時刻)向けのものかを表す識別文字列
    private final Map<String, String> registeredSlotKey = new HashMap<>();

    // プレイヤーUUID -> 今の枠でロックしているbossId (1枠1フロア制限用)
    private final Map<UUID, String> playerLockedBoss = new HashMap<>();
    // プレイヤーUUID -> ロックしている枠の識別文字列
    private final Map<UUID, String> playerLockedSlotKey = new HashMap<>();

    // bossId -> 直近にこのマネージャーが「開始処理」を実行した枠の識別文字列(二重発火防止)
    private final Map<String, String> lastTriggeredSlotKey = new HashMap<>();

    // ★ボスエンティティUUID -> (プレイヤーUUID -> 与えた合計ダメージ)
    private final Map<UUID, Map<UUID, Double>> bossDamage = new HashMap<>();

    public RaidManager(SACplugin plugin) {
        this.plugin = plugin;
        loadState();
        startScheduler();
    }

    // ==========================================
    // 時刻計算
    // ==========================================

    /**
     * 現在時刻以降(同時刻含む)で最も近いレイド開始時刻を返す
     */
    private LocalDateTime getNextRaidTime(LocalDateTime now) {
        LocalDateTime today = now.toLocalDate().atStartOfDay();
        List<LocalDateTime> candidates = new ArrayList<>();
        // 今日と明日の分を候補に入れておけば、日をまたいでも必ず見つかる
        for (int dayOffset = 0; dayOffset <= 1; dayOffset++) {
            LocalDateTime day = today.plusDays(dayOffset);
            for (int hour : RAID_HOURS) {
                // ★修正: 万が一hourに24(「深夜24時=翌0時」表記など)以上の値が
                // 渡されてもクラッシュしないよう、日付超過分をplusDaysで吸収してから
                // withHour(0-23の範囲)する。
                LocalDateTime candidate = day.plusDays(Math.floorDiv(hour, 24)).withHour(Math.floorMod(hour, 24));
                candidates.add(candidate);
            }
        }
        candidates.sort(Comparator.naturalOrder());
        for (LocalDateTime c : candidates) {
            if (!c.isBefore(now)) return c;
        }
        // 理論上ここには到達しない(明日分まで候補に入れているため)が、保険としてフォールバック
        return candidates.get(candidates.size() - 1);
    }

    private String slotKey(LocalDateTime raidTime) {
        return raidTime.format(SLOT_FMT);
    }

    /**
     * このボスの次回レイド戦闘開始時刻を返す(GUI表示などに使える)
     */
    public LocalDateTime getNextRaidTime() {
        return getNextRaidTime(LocalDateTime.now());
    }

    // ==========================================
    // 登録処理
    // ==========================================

    /**
     * プレイヤーがそのフロアボスのレイドに参加登録を試みる。
     * 結果メッセージ(そのままプレイヤーに送ってよい文言)を返す。
     */
    public String tryRegister(Player player, String bossId) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime next = getNextRaidTime(now);
        long minutesUntil = Duration.between(now, next).toMinutes();

        if (minutesUntil > REGISTRATION_WINDOW_MINUTES) {
            LocalDateTime opensAt = next.minusMinutes(REGISTRATION_WINDOW_MINUTES);
            return "§c[レイド] このフロアボスの次回受付開始は §e" + opensAt.format(TIME_FMT)
                    + " §cからです。(戦闘開始 " + next.format(TIME_FMT) + ")";
        }

        String slot = slotKey(next);
        UUID uuid = player.getUniqueId();

        // ★バグ対策: 古い枠のロックが残っていたら(何らかの理由でクリアされ損ねていた場合)、
        // 枠が変わっていれば無効化してから判定する。
        String lockedSlot = playerLockedSlotKey.get(uuid);
        String lockedBoss = playerLockedBoss.get(uuid);
        if (lockedSlot != null && !lockedSlot.equals(slot)) {
            playerLockedSlotKey.remove(uuid);
            playerLockedBoss.remove(uuid);
            lockedSlot = null;
            lockedBoss = null;
        }

        if (lockedSlot != null && lockedBoss != null) {
            if (lockedBoss.equals(bossId)) {
                return "§e[レイド] 既に「" + bossId + "」のレイドに参加登録済みです。(" + next.format(TIME_FMT) + " 開始)";
            } else {
                return "§c[レイド] この時間枠(" + next.format(TIME_FMT) + ")では既に「" + lockedBoss
                        + "」のレイドに参加登録済みのため、「" + bossId + "」には参加できません。";
            }
        }

        // ★バグ対策: このbossIdの登録リストが古い枠のまま残っていた場合(前回トリガー時のクリア漏れ等)は、
        // 新しい枠のものとして扱えるよう先にクリアしておく。
        if (!slot.equals(registeredSlotKey.get(bossId))) {
            registeredPlayers.remove(bossId);
            registeredSlotKey.put(bossId, slot);
        }

        registeredPlayers.computeIfAbsent(bossId, k -> new HashSet<>()).add(uuid);
        playerLockedBoss.put(uuid, bossId);
        playerLockedSlotKey.put(uuid, slot);

        saveState();

        return "§a[レイド] 「" + bossId + "」のレイドに参加登録しました！ §e" + next.format(TIME_FMT)
                + " §aまでにボス部屋の入り口付近にいてください。";
    }

    // ==========================================
    // スケジューラ(開始時刻の監視)
    // ==========================================
    private void startScheduler() {
        new BukkitRunnable() {
            int tickCounter = 0;

            @Override
            public void run() {
                LocalDateTime now = LocalDateTime.now();
                for (int hour : RAID_HOURS) {
                    if (now.getHour() == hour && now.getMinute() == 0) {
                        triggerAllRaidsForSlot(now.withMinute(0).withSecond(0).withNano(0));
                    }
                }

                // ★追加: ダメージ記録は毎ヒット保存すると重いため、約10秒に1回だけ保存する。
                // (再起動が発生してもここ数秒分の記録しか失われない程度に抑える)
                tickCounter++;
                if (tickCounter >= 10) {
                    tickCounter = 0;
                    saveState();
                }
            }
        }.runTaskTimer(plugin, 20L, 20L); // 1秒ごとにチェック
    }

    private void triggerAllRaidsForSlot(LocalDateTime slotTime) {
        String slot = slotKey(slotTime);

        // registeredSlotKeyに載っている(=誰かしら登録がある)bossIdだけを対象にする
        for (String bossId : new ArrayList<>(registeredSlotKey.keySet())) {
            if (!slot.equals(registeredSlotKey.get(bossId))) continue;
            // ★バグ対策: 1秒間隔で回っているため、同じ分の間に何度も条件成立してしまう可能性がある。
            // 同じ枠に対して二重に開始処理をしないようにする。
            if (slot.equals(lastTriggeredSlotKey.get(bossId))) continue;
            lastTriggeredSlotKey.put(bossId, slot);

            Set<UUID> participants = registeredPlayers.getOrDefault(bossId, Collections.emptySet());
            if (!participants.isEmpty()) {
                BossData data = plugin.getBossManager().getBossData(bossId);
                if (data != null) {
                    startRaidFight(bossId, data, new HashSet<>(participants));
                }
            }

            // 使い終わった登録情報はクリアし、次の枠でまた新規に登録させる
            registeredPlayers.remove(bossId);
            registeredSlotKey.remove(bossId);
            for (UUID uuid : participants) {
                playerLockedBoss.remove(uuid);
                playerLockedSlotKey.remove(uuid);
            }
        }

        saveState();
    }

    private void startRaidFight(String bossId, BossData data, Set<UUID> participants) {
        // ★バグ対策: 前回の個体がまだ生存中(討伐しきれなかった等)の場合、
        // 二重スポーン/二重戦闘を避けるためこの回の開始は見送る。
        boolean alreadyAlive = false;
        org.bukkit.World world = Bukkit.getWorld(data.world);
        if (world != null) {
            for (LivingEntity entity : world.getLivingEntities()) {
                if (entity.getScoreboardTags().contains("sao_mob_" + bossId)) {
                    alreadyAlive = true;
                    break;
                }
            }
        }

        Location entrance = findHologramLocation(bossId);

        List<Player> arrived = new ArrayList<>();
        List<Player> missed = new ArrayList<>();

        for (UUID uuid : participants) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline()) continue;

            if (alreadyAlive) {
                p.sendMessage("§c[System] 「" + bossId + "」は現在別のパーティーが戦闘中のため、今回の開始は見送られました。");
                continue;
            }

            if (entrance != null && p.getWorld().equals(entrance.getWorld())
                    && p.getLocation().distanceSquared(entrance) <= ARRIVAL_RADIUS * ARRIVAL_RADIUS) {
                arrived.add(p);
            } else {
                missed.add(p);
            }
        }

        for (Player p : missed) {
            p.sendMessage("§c[System] 開始時刻にボス部屋の入り口付近にいなかったため、「" + bossId + "」のレイドに参加できませんでした。");
        }

        if (alreadyAlive || arrived.isEmpty()) return;

        Location tpLoc = new Location(Bukkit.getWorld(data.world), data.tpInX, data.tpInY, data.tpInZ, data.tpInYaw, data.tpInPitch);
        for (Player p : arrived) {
            p.teleport(tpLoc);
            p.sendMessage("§c[System] ボス部屋へ転移しました");
            p.sendTitle("§cFLOOR BOSS ", "", 10, 60, 10);
            p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 0.5f);
            p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.5f, 0.8f);
        }
    }

    private Location findHologramLocation(String bossId) {
        for (org.bukkit.World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntitiesByClass(ArmorStand.class)) {
                if (e.getScoreboardTags().contains("sao_boss_tp_" + bossId)) {
                    return e.getLocation();
                }
            }
        }
        return null;
    }

    // ==========================================
    // ダメージ記録(討伐報酬の倍率計算に使う)
    // ==========================================

    /**
     * プレイヤーがボス個体(entityUuid)に与えたダメージを加算記録する
     */
    public void recordBossDamage(UUID bossEntityUuid, Player player, double damage) {
        if (damage <= 0) return;
        bossDamage.computeIfAbsent(bossEntityUuid, k -> new HashMap<>())
                .merge(player.getUniqueId(), damage, Double::sum);
    }

    /**
     * プレイヤーがそのボス個体に与えた合計ダメージ(記録が無ければ0)
     */
    public double getPlayerDamage(UUID bossEntityUuid, UUID playerUuid) {
        Map<UUID, Double> map = bossDamage.get(bossEntityUuid);
        if (map == null) return 0;
        return map.getOrDefault(playerUuid, 0.0);
    }

    /**
     * そのボス個体の討伐処理が終わったら必ず呼び出し、記録を破棄する(メモリリーク防止)
     */
    public void clearBossDamage(UUID bossEntityUuid) {
        bossDamage.remove(bossEntityUuid);
        saveState();
    }

    // ==========================================
    // ★追加: 再起動をまたいでもレイドの状態が消えないよう、JSONへ保存・復元する。
    // 保存タイミング: 登録成功時、枠の開始処理完了時、ボス討伐時、約10秒ごと、プラグイン終了時。
    // ==========================================
    public void saveState() {
        File file = new File(plugin.getDataFolder(), "raid_state.json");
        JsonObject root = new JsonObject();

        JsonObject regPlayers = new JsonObject();
        for (Map.Entry<String, Set<UUID>> e : registeredPlayers.entrySet()) {
            JsonArray arr = new JsonArray();
            for (UUID u : e.getValue()) arr.add(u.toString());
            regPlayers.add(e.getKey(), arr);
        }
        root.add("registeredPlayers", regPlayers);

        JsonObject regSlot = new JsonObject();
        for (Map.Entry<String, String> e : registeredSlotKey.entrySet()) regSlot.addProperty(e.getKey(), e.getValue());
        root.add("registeredSlotKey", regSlot);

        JsonObject lockedBoss = new JsonObject();
        for (Map.Entry<UUID, String> e : playerLockedBoss.entrySet())
            lockedBoss.addProperty(e.getKey().toString(), e.getValue());
        root.add("playerLockedBoss", lockedBoss);

        JsonObject lockedSlot = new JsonObject();
        for (Map.Entry<UUID, String> e : playerLockedSlotKey.entrySet())
            lockedSlot.addProperty(e.getKey().toString(), e.getValue());
        root.add("playerLockedSlotKey", lockedSlot);

        JsonObject lastTrig = new JsonObject();
        for (Map.Entry<String, String> e : lastTriggeredSlotKey.entrySet())
            lastTrig.addProperty(e.getKey(), e.getValue());
        root.add("lastTriggeredSlotKey", lastTrig);

        JsonObject dmg = new JsonObject();
        for (Map.Entry<UUID, Map<UUID, Double>> e : bossDamage.entrySet()) {
            JsonObject inner = new JsonObject();
            for (Map.Entry<UUID, Double> e2 : e.getValue().entrySet()) {
                inner.addProperty(e2.getKey().toString(), e2.getValue());
            }
            dmg.add(e.getKey().toString(), inner);
        }
        root.add("bossDamage", dmg);

        try (FileWriter writer = new FileWriter(file)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(root, writer);
        } catch (Exception e) {
            plugin.getLogger().warning("[Raid] raid_state.json の保存に失敗しました: " + e.getMessage());
        }
    }

    private void loadState() {
        File file = new File(plugin.getDataFolder(), "raid_state.json");
        if (!file.exists()) return;

        try (FileReader reader = new FileReader(file)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();

            if (root.has("registeredPlayers")) {
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("registeredPlayers").entrySet()) {
                    Set<UUID> set = new HashSet<>();
                    for (JsonElement je : e.getValue().getAsJsonArray()) {
                        try {
                            set.add(UUID.fromString(je.getAsString()));
                        } catch (Exception ignored) {
                        }
                    }
                    registeredPlayers.put(e.getKey(), set);
                }
            }
            if (root.has("registeredSlotKey")) {
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("registeredSlotKey").entrySet()) {
                    registeredSlotKey.put(e.getKey(), e.getValue().getAsString());
                }
            }
            if (root.has("playerLockedBoss")) {
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("playerLockedBoss").entrySet()) {
                    try {
                        playerLockedBoss.put(UUID.fromString(e.getKey()), e.getValue().getAsString());
                    } catch (Exception ignored) {
                    }
                }
            }
            if (root.has("playerLockedSlotKey")) {
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("playerLockedSlotKey").entrySet()) {
                    try {
                        playerLockedSlotKey.put(UUID.fromString(e.getKey()), e.getValue().getAsString());
                    } catch (Exception ignored) {
                    }
                }
            }
            if (root.has("lastTriggeredSlotKey")) {
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("lastTriggeredSlotKey").entrySet()) {
                    lastTriggeredSlotKey.put(e.getKey(), e.getValue().getAsString());
                }
            }
            if (root.has("bossDamage")) {
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("bossDamage").entrySet()) {
                    Map<UUID, Double> inner = new HashMap<>();
                    for (Map.Entry<String, JsonElement> e2 : e.getValue().getAsJsonObject().entrySet()) {
                        try {
                            inner.put(UUID.fromString(e2.getKey()), e2.getValue().getAsDouble());
                        } catch (Exception ignored) {
                        }
                    }
                    try {
                        bossDamage.put(UUID.fromString(e.getKey()), inner);
                    } catch (Exception ignored) {
                    }
                }
            }

            plugin.getLogger().info("[Raid] raid_state.json を読み込みました。(登録中のレイド: "
                    + registeredSlotKey.size() + "件, ダメージ記録: " + bossDamage.size() + "体分)");
        } catch (Exception e) {
            plugin.getLogger().warning("[Raid] raid_state.json の読み込みに失敗しました: " + e.getMessage());
        }
    }
}