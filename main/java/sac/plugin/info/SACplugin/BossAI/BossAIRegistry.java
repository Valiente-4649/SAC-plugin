package sac.plugin.info.SACplugin.BossAI;

import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import sac.plugin.info.SACplugin.SACplugin;
import sac.plugin.info.SACplugin.boss.BossData;
import sac.plugin.info.SACplugin.mob.SAOMobData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class BossAIRegistry implements Listener {
    private final SACplugin plugin;

    // ボスエンティティUUID → AI インスタンス
    private final Map<UUID, FloorBossAI> activeAIs = new HashMap<>();
    // ボスエンティティUUID → タスク
    private final Map<UUID, BukkitTask> activeTasks = new HashMap<>();
    // ボスエンティティUUID → モブID（死亡イベントから逆引きするため）
    private final Map<UUID, String> activeMobIds = new HashMap<>();
    // ★追加: EntityDeathEventで既に討伐処理済みかどうかのフラグ（二重処理防止）
    private final Map<UUID, Boolean> deathHandled = new HashMap<>();

    public BossAIRegistry(SACplugin plugin) {
        this.plugin = plugin;
        // ★ 自身をリスナーとして登録する必要がある（SACplugin.javaでも登録推奨）
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    /**
     * ボスIDに対応するAIを生成してタスクを開始する。
     * SAOMobManager.spawnMob から呼ぶか、SAOMobManager を経由して呼ぶ。
     */
    public void registerBoss(String mobId, LivingEntity entity) {
        SAOMobData mobData = plugin.getSaoMobManager().getMobData(mobId);
        if (mobData == null) return;

        BossData bossData = plugin.getBossManager().getBossData(mobId);
        // bossData が null でも動作する (Nato/Baran/Asterios は boss.json に登録任意)

        FloorBossAI ai = createAI(mobId, entity, mobData, bossData);
        if (ai == null) return;

        UUID uuid = entity.getUniqueId();
        activeAIs.put(uuid, ai);
        activeMobIds.put(uuid, mobId);
        deathHandled.put(uuid, false);

        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                // ★修正: 死亡判定はEntityDeathEventリスナー(onBossDeath)に一任する。
                // entity.isDead()/getHealth()のポーリングはタイミング競合(removeとの競合)で
                // 不安定になり、Baran/Asteriosのスポーンが行われない原因となっていた。
                // ここでは「エンティティが無効になった」場合にタスクをクリーンアップするだけに留める。
                if (!entity.isValid()) {
                    this.cancel();
                    activeAIs.remove(uuid);
                    activeTasks.remove(uuid);
                    activeMobIds.remove(uuid);
                    deathHandled.remove(uuid);
                    return;
                }
                ai.tick();
            }
        }.runTaskTimer(plugin, 1L, 1L); // 毎tick

        activeTasks.put(uuid, task);
        plugin.getLogger().info("[BossAI] " + mobId + " のAIを起動しました。");
    }

    /**
     * ★追加: EntityDeathEventで確実に討伐を検知する。
     * SAOMobListener.onMobDeathより先に処理されるよう優先度を高めに設定。
     * これにより entity.remove() が走る前の確実なタイミングで討伐処理を実行できる。
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onBossDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        UUID uuid = entity.getUniqueId();

        String mobId = activeMobIds.get(uuid);
        if (mobId == null) return; // 管理対象外のエンティティ

        // 二重処理防止
        if (Boolean.TRUE.equals(deathHandled.get(uuid))) return;
        deathHandled.put(uuid, true);

        FloorBossAI ai = activeAIs.get(uuid);
        if (ai != null) {
            handleDeath(mobId, entity, ai);
        }

        // タスク・管理データをクリーンアップ
        BukkitTask task = activeTasks.remove(uuid);
        if (task != null) task.cancel();
        activeAIs.remove(uuid);
        activeMobIds.remove(uuid);
        deathHandled.remove(uuid);
    }

    /** ボスIDに対応する AI クラスを生成 */
    private FloorBossAI createAI(String mobId, LivingEntity entity, SAOMobData mobData, BossData bossData) {
        switch (mobId) {
            case "nato_the_colonel_taurus":
                return new NatoAI(plugin, entity, mobData, bossData);
            case "baran_the_general_taurus":
                return new BaranAI(plugin, entity, mobData, bossData);
            case "asterios_the_taurus_king":
                return new AsteriosAI(plugin, entity, mobData, bossData);
            case "nerius_the_evil_treant":
                return new NeriusAI(plugin, entity, mobData, bossData);
            default:
                return null; // 対象外のモブは無視
        }
    }

    /** 死亡時の後処理 */
    private void handleDeath(String mobId, LivingEntity entity, FloorBossAI ai) {
        switch (mobId) {
            case "nato_the_colonel_taurus":
                ((NatoAI) ai).onDeath();
                break;
            case "baran_the_general_taurus":
                ((BaranAI) ai).onDeath();
                // Asterios は第二層のフロアボスとして handleBossDefeat も呼ぶ
                break;
            case "asterios_the_taurus_king":
                // 撃破後の街TPは BossManager に任せる
                plugin.getBossManager().handleBossDefeat(mobId);
                // 第二層の解放記録
                for (org.bukkit.entity.Player p : plugin.getBossManager().getPlayersInBossRoom(mobId)) {
                    plugin.getCrystalManager().recordBossDefeat(p, mobId);
                }
                break;
            case "nerius_the_evil_treant":
                ((NeriusAI) ai).onDeath();
                // 3層のフロアボスとして撃破記録・街TPを行う
                plugin.getBossManager().handleBossDefeat(mobId);
                for (org.bukkit.entity.Player p : plugin.getBossManager().getPlayersInBossRoom(mobId)) {
                    plugin.getCrystalManager().recordBossDefeat(p, mobId);
                }
                break;
        }
    }

    public boolean isFloorBoss(String mobId) {
        return mobId.equals("nato_the_colonel_taurus")
                || mobId.equals("baran_the_general_taurus")
                || mobId.equals("asterios_the_taurus_king")
                || mobId.equals("nerius_the_evil_treant");
    }
}