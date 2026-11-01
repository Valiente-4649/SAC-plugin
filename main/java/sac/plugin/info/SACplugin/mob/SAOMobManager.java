package sac.plugin.info.SACplugin.mob;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import sac.plugin.info.SACplugin.Player.PlayerData;
import sac.plugin.info.SACplugin.SACplugin;
import sac.plugin.info.SACplugin.boss.BossData;
import sac.plugin.info.SACplugin.util.Reloadable;

import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Type;
import java.util.*;

public class SAOMobManager implements Reloadable {
    private final SACplugin plugin;
    private final Map<String, SAOMobData> mobTemplates = new HashMap<>();
    private final Random random = new Random();

    public SAOMobManager(SACplugin plugin) {
        this.plugin = plugin;
        loadMobs();
        startAILoop();
    }

    private void startAILoop() {
        new BukkitRunnable() {
            @Override
            public void run() {
                // 全ワールドの全エンティティをチェック
                for (org.bukkit.World world : org.bukkit.Bukkit.getWorlds()) {
                    for (org.bukkit.entity.LivingEntity entity : world.getLivingEntities()) {
                        try {
                            // 死んでいる、またはモブじゃない(プレイヤー等)なら無視
                            if (entity.isDead() || !entity.isValid() || !(entity instanceof org.bukkit.entity.Mob)) continue;
                            org.bukkit.entity.Mob mob = (org.bukkit.entity.Mob) entity;

                            // SAOモブかどうか判定
                            String mobId = null;
                            for (String tag : mob.getScoreboardTags()) {
                                if (tag.startsWith("sao_mob_")) {
                                    mobId = tag.replace("sao_mob_", "");
                                    break;
                                }
                            }
                            if (mobId == null) continue;
                            SAOMobData data = getMobData(mobId);
                            if (data == null) continue;

                            // ★ BossAIRegistry が管理するフロアボスは通常AIループをスキップ
                            if (plugin.getBossAIRegistry() != null && plugin.getBossAIRegistry().isFloorBoss(mobId)) continue;

                            // ==========================================
                            // ★ 修正: ターゲットがいない、またはログアウトしたプレイヤーなら「リセット」する
                            // ==========================================
                            if (mob.getTarget() == null || (mob.getTarget() instanceof Player && !((Player) mob.getTarget()).isOnline())) {

                                // 1. スキルのクールタイム情報を消去（これで「未発見状態」に戻る）
                                if (mob.hasMetadata("sao_skill_cd")) {
                                    mob.removeMetadata("sao_skill_cd", plugin);
                                }

                                // 2. もし詠唱中(sao_casting)なら、そのタグを外す
                                if (mob.getScoreboardTags().contains("sao_casting")) {
                                    mob.removeScoreboardTag("sao_casting");
                                }

                                // 3. 足が止まっていたら移動速度を元に戻す
                                if (mob.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).getBaseValue() <= 0.0) {
                                    mob.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).setBaseValue(data.movementSpeed);
                                }

                                continue; // このモブの処理はここで終わり（平和な状態）
                            }
                            // ==========================================
                            // ★新規追加: ターゲットを追跡中の足音！
                            // 詠唱中(足が止まっている状態)じゃなければ音を鳴らす
                            // ==========================================
                            if (!mob.getScoreboardTags().contains("sao_casting") && mob.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).getBaseValue() > 0.0) {
                                // やや低く重い足音を鳴らす (音量0.5, ピッチ0.8)
                                // ==========================================
                                // ★変更  後: 硬いブーツや石のような、乾いた「ドスッ」という音
                                // ==========================================
                                mob.getWorld().playSound(mob.getLocation(), org.bukkit.Sound.BLOCK_STONE_STEP, 0.6f, 0.7f);
                            }

                            // ターゲットとの距離が遠すぎたら使わない (15ブロック以内でのみ発動)
                            if (mob.getLocation().distanceSquared(mob.getTarget().getLocation()) > 225) continue;

                            // すでに詠唱中（硬直中）なら新しいスキルは使わない
                            if (mob.getScoreboardTags().contains("sao_casting")) continue;

                            // ★追加: パリィ成功で硬直中(sao_parried)のモブも特殊攻撃を使わせない
                            if (mob.getScoreboardTags().contains("sao_parried")) continue;

                            if (data.specialAttacks == null || data.specialAttacks.isEmpty()) continue;

                            // --- 10秒のクールダウン判定 ---
                            long currentTime = System.currentTimeMillis();

                            // ★ここでリセットされているので、再ログイン後は必ずここを通る
                            if (!mob.hasMetadata("sao_skill_cd")) {
                                // 初めて見つけた時は5秒間の猶予を与える
                                mob.setMetadata("sao_skill_cd", new org.bukkit.metadata.FixedMetadataValue(plugin, currentTime + 4000L));
                                continue;
                            }

                            long cdTime = mob.getMetadata("sao_skill_cd").get(0).asLong();
                            if (currentTime < cdTime) continue;

                            mob.setMetadata("sao_skill_cd", new org.bukkit.metadata.FixedMetadataValue(plugin, currentTime + 6000L));

                            MobSkillData skill = data.specialAttacks.get(random.nextInt(data.specialAttacks.size()));

                            org.bukkit.util.Vector direction = mob.getTarget().getLocation().toVector().subtract(mob.getLocation().toVector());
                            direction.setY(0);

                            if (direction.lengthSquared() > 0) {
                                Location loc = mob.getLocation();
                                loc.setDirection(direction);
                                mob.teleport(loc);
                            }

                            SAOMobSkillExecutor.castSkill(plugin, mob, skill, data);

                        } catch (Exception e) {
                            // エラーが起きてもシステムを止めない
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 20L); // 1秒ごとにチェック
    }

    public void loadMobs() {
        File file = new File(plugin.getDataFolder(), "mobs.json");
        if (!file.exists()) plugin.saveResource("mobs.json", false);

        try (FileReader reader = new FileReader(file)) {
            Type type = new TypeToken<Map<String, SAOMobData>>(){}.getType();
            Map<String, SAOMobData> loaded = new Gson().fromJson(reader, type);
            if (loaded != null) {
                mobTemplates.clear();
                mobTemplates.putAll(loaded);
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    @Override
    public void reload() {
        loadMobs();
    }

    public void spawnMob(String id, Location loc) {
        SAOMobData data = mobTemplates.get(id);
        if (data == null) return;

        Zombie zombie = (Zombie) loc.getWorld().spawnEntity(loc, EntityType.ZOMBIE);

        zombie.getEquipment().clear(); // 武器や防具をすべて消す
        zombie.setCanPickupItems(false); // 落ちているアイテムを拾わせない
        zombie.setSilent(true);
        zombie.setBaby(false);

        zombie.addScoreboardTag("sao_mob_" + data.id);

        // 名前を設定 [Lv.1] Frenzy Boar
        zombie.setCustomName("§7[Lv." + data.level + "] §f" + data.name);
        zombie.setCustomNameVisible(true);

        // ステータス設定
        zombie.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(data.maxHealth);
        // ★修正: spigot.ymlのattribute.maxHealth.max設定(デフォルト1024〜2048)を超える値を
        // setHealthに渡すとIllegalArgumentExceptionが発生し、それ以降の処理(ヘルメット設定・
        // BossAI登録など)が全てスキップされてしまっていた(ボスのテクスチャ消失・透明化の原因)。
        // spigot.ymlのattribute.maxHealth.maxを十分大きい値に設定することが本来の対処だが、
        // 設定漏れがあっても処理が完全停止しないようtry-catchで保護する。
        try {
            zombie.setHealth(data.maxHealth);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().severe("[SAO] " + data.id + " のHP設定(" + data.maxHealth
                    + ")がspigot.ymlのattribute.maxHealth.max上限を超えています！"
                    + " spigot.ymlのsettings.attribute.maxHealth.maxを十分大きい値に設定してください。");
            // 上限値に近い安全な値でフォールバックし、処理を継続させる
            try {
                zombie.setHealth(zombie.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue());
            } catch (Exception fallbackEx) {
                plugin.getLogger().severe("[SAO] " + data.id + " のHP設定フォールバックも失敗しました。");
            }
        }
        zombie.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE).setBaseValue(data.attackDamage);
        zombie.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).setBaseValue(data.movementSpeed);

        if (data.followRange > 0) {
            zombie.getAttribute(Attribute.GENERIC_FOLLOW_RANGE).setBaseValue(data.followRange);
        }

        // 見た目設定（バリアブロックを被せる）
        ItemStack helmet = new ItemStack(Material.BARRIER);
        ItemMeta meta = helmet.getItemMeta();
        if (meta != null) {
            meta.setCustomModelData(data.customModelData);
            helmet.setItemMeta(meta);
        }

        // ★修正: ボスのみテクスチャが消える(透明になる)現象が確認されたため、
        // スポーン直後の同tick内でgetEquipment()を呼ぶタイミング依存の問題を疑い、
        // 1tick遅らせて確実にエンティティが完全初期化された後に装備をセットする。
        // 通常モブは正常表示されていたが、ボスはregisterBossの追加処理が同tick内に重なるため
        // タイミングがズレやすい可能性がある。
        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
            if (!zombie.isValid()) return;
            if (zombie.getEquipment() != null) {
                zombie.getEquipment().setHelmet(helmet);
                zombie.getEquipment().setHelmetDropChance(0.0f);
            }
            updateHpBar(zombie);

            // ★ フロアボスAIの対象であれば専用AIを起動
            if (plugin.getBossAIRegistry() != null && plugin.getBossAIRegistry().isFloorBoss(data.id)) {
                plugin.getBossAIRegistry().registerBoss(data.id, zombie);
            }
        });
    }
    public void updateHpBar(LivingEntity entity) {
        double max = entity.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue();
        double current = entity.getHealth();

        // ==========================================
        // ★追加: フロアボスはバニラのボスバー(BossBarManager)で表示するため、
        // 通常モブ用の「胸装備テクスチャ+名前バー」方式はここで打ち切る。
        // ==========================================
        String mobId = null;
        for (String tag : entity.getScoreboardTags()) {
            if (tag.startsWith("sao_mob_")) {
                mobId = tag.replace("sao_mob_", "");
                break;
            }
        }
        // ==========================================
        // ★修正: フロアボスの判定をBossAIRegistry.isFloorBoss()(専用AIクラスを持つ
        // Nato/Baran/Asteriosのみ)から、BossManager.getBossData()の有無に変更。
        // イルファングのように専用AIを持たないボスもboss.jsonには登録されているため、
        // こちらの判定でないと漏れてしまう。
        // ==========================================
        if (mobId != null && plugin.getBossManager() != null && plugin.getBossManager().getBossData(mobId) != null) {
            plugin.getBossBarManager().updateBossBar(entity, mobId, current, max);
            return;
        }

        // 割合を計算 (0.0 ～ 1.0)
        double ratio = current / max;

        // 1～10の段階に変換 (例: 0.95 -> 10, 0.45 -> 5)
        int step = (int) Math.ceil(ratio * 10);

        // 範囲外の補正
        if (step > 10) step = 10;
        if (step < 1) step = 1; // HPが0になる瞬間はDeathEventで処理されるので、ここでは最低1を表示

        // モデルIDの計算 (5000 + 段階)
        int cmd = 5000 + step;

        ItemStack bar = new ItemStack(Material.CARROT_ON_A_STICK);
        ItemMeta meta = bar.getItemMeta();
        if (meta != null) {
            meta.setCustomModelData(cmd);
            meta.setDisplayName("§cHP: " + (int)current + " / " + (int)max); // カーソルを合わせると数値が見える
            meta.setUnbreakable(true);
            bar.setItemMeta(meta);
        }

        // チェストにセット
        if (entity.getEquipment() != null) {
            entity.getEquipment().setChestplate(bar);
            entity.getEquipment().setChestplateDropChance(0.0f); // ドロップさせない
        }
        // ==========================================
        // ★追加: ここから下に、テキストのHPバー(|||||||)の処理を追記
        // ==========================================
        String customName = entity.getCustomName();
        if (customName != null) {
            String separator = "  "; // スペース2つを区切り文字にする
            String baseName = customName;

            // 既にバーが付いている場合は、古いバーを切り離して本来の名前だけを取り出す
            if (customName.contains(separator)) {
                baseName = customName.split(separator)[0];
            }

            // バーを生成して、名前の後ろにくっつける
            String textBar = sac.plugin.info.SACplugin.ui.SAOHPDisplay.generateHpBar(current, max);
            entity.setCustomName(baseName + separator + textBar);
        }
    }
    public SAOMobData getMobData(String id) {
        return mobTemplates.get(id); // 保存されているモブデータからIDで検索して返す
    }
    public String getMobCategory(LivingEntity entity) {
        for (String tag : entity.getScoreboardTags()) {
            if (tag.startsWith("sao_mob_")) {
                String id = tag.replace("sao_mob_", "");
                SAOMobData data = getMobData(id);
                if (data != null) {
                    return data.category != null ? data.category : "NORMAL";
                }
            }
        }
        return "NORMAL";
    }
}