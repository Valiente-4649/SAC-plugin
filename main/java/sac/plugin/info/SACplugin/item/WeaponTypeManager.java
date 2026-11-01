package sac.plugin.info.SACplugin.item;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import sac.plugin.info.SACplugin.SACplugin;
import sac.plugin.info.SACplugin.util.Reloadable;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class WeaponTypeManager implements Reloadable {
    private final SACplugin plugin;
    private final Map<String, WeaponTypeData> weaponTypes = new HashMap<>();

    // AttributeModifier用の固定UUID（武器リーチ専用）
    private static final UUID REACH_UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    private static final String REACH_KEY = "SAO_WEAPON_REACH";

    // バニラのデフォルトリーチ（1.21.1のPLAYER_ENTITY_INTERACTION_RANGEのデフォルト値(1.21.1)）
    private static final double DEFAULT_REACH = 3.0;

    public WeaponTypeManager(SACplugin plugin) {
        this.plugin = plugin;
        load();
    }

    /** weapon_types.json を再読み込みする（jarは変えず、データだけ更新したい時用） */
    @Override
    public void reload() {
        weaponTypes.clear();
        load();
    }

    private void load() {
        try {
            InputStream is = getClass().getClassLoader().getResourceAsStream("weapon_types.json");
            if (is == null) {
                // リソースフォルダにない場合はプラグインフォルダから読む
                java.io.File file = new java.io.File(plugin.getDataFolder(), "weapon_types.json");
                if (!file.exists()) {
                    plugin.getLogger().warning("[WeaponType] weapon_types.json が見つかりません。");
                    return;
                }
                is = new java.io.FileInputStream(file);
            }
            Gson gson = new Gson();
            Type listType = new TypeToken<List<WeaponTypeData>>() {}.getType();
            List<WeaponTypeData> list = gson.fromJson(new InputStreamReader(is, "UTF-8"), listType);
            for (WeaponTypeData data : list) {
                weaponTypes.put(data.category, data);
            }
            plugin.getLogger().info("[WeaponType] 武器種データを " + weaponTypes.size() + " 件読み込みました。");
        } catch (Exception e) {
            plugin.getLogger().severe("[WeaponType] weapon_types.json の読み込みに失敗しました: " + e.getMessage());
        }
    }

    /** 武器種名からデータを取得する */
    public WeaponTypeData getType(String category) {
        return weaponTypes.get(category);
    }

    /** 武器種名からリーチを取得する（定義がなければデフォルト値） */
    public double getReach(String category) {
        WeaponTypeData data = weaponTypes.get(category);
        return data != null ? data.normalReach : DEFAULT_REACH;
    }

    // バニラの攻撃速度アトリビュートのベース値（GENERIC_ATTACK_SPEEDのデフォルト = 4.0）
    private static final double BASE_ATTACK_SPEED_ATTRIBUTE = 4.0;

    // weapon_types.jsonでmaxSpeedBpmが未設定(0以下)の場合に使うデフォルトBPM
    private static final double DEFAULT_MAX_SPEED_BPM = 140.0;

    /**
     * 武器種名から「最速値（speedRatioの上限）」を取得する。
     * BPM(4/4拍子、1拍=1回攻撃)を「1秒あたりの攻撃回数」に変換し、
     * さらにSAOSTRListenerが使うspeedRatio（アトリビュート最終値 = 4.0 × ratio）に変換する。
     *
     * 例: 140BPM -> 140/60 = 2.333回/秒 -> ratio上限 = 2.333/4.0 = 0.583
     */
    public double getMaxSpeedRatio(String category) {
        WeaponTypeData data = weaponTypes.get(category);
        double bpm = (data != null && data.maxSpeedBpm > 0) ? data.maxSpeedBpm : DEFAULT_MAX_SPEED_BPM;
        double attacksPerSecond = bpm / 60.0;
        double ratio = attacksPerSecond / BASE_ATTACK_SPEED_ATTRIBUTE;
        // 安全のためバニラ最速(ratio=1.0)を超えないようにする
        return Math.min(ratio, 1.0);
    }

    /**
     * 武器種名から「重量が振り速度計算に影響するか」を取得する。
     * 定義がない場合はtrue（従来通り重量が影響する）を返す。
     */
    public boolean isWeightAffectingSpeed(String category) {
        WeaponTypeData data = weaponTypes.get(category);
        return data == null || data.weightAffectsSpeed;
    }

    /**
     * 武器種名から「STRが振り速度計算に影響するか」を取得する。
     * falseの場合、その武器種はSTR・重量に関わらず常に最速値(maxSpeedBpm)固定で振れる。
     * 定義がない場合はtrue（従来通りSTRが影響する）を返す。
     */
    public boolean isStrAffectingSpeed(String category) {
        WeaponTypeData data = weaponTypes.get(category);
        return data == null || data.strAffectsSpeed;
    }

    /**
     * 武器種名から「ノックバックするかどうか」を取得する。
     * falseの場合、その武器種の攻撃は被弾者を一切怯ませない。
     * 定義がない場合はtrue（通常通りノックバックする）を返す。
     */
    public boolean isKnockbackEnabled(String category) {
        WeaponTypeData data = weaponTypes.get(category);
        return data == null || data.knockback;
    }

    /**
     * 武器種名から「パリィ(受け流し)が可能かどうか」を取得する。
     * 定義がない場合はfalse（パリィ不可）を返す。
     */
    public boolean canParry(String category) {
        WeaponTypeData data = weaponTypes.get(category);
        return data != null && data.canParry;
    }

    /** 全武器種データのマップを返す */
    public Map<String, WeaponTypeData> getAllTypes() {
        return weaponTypes;
    }

    /** 指定した武器種が「素手運用(体術など)」かどうかを返す */
    public boolean isUnarmed(String category) {
        WeaponTypeData data = weaponTypes.get(category);
        return data != null && data.unarmed;
    }

    /**
     * 素手武器種の、指定した熟練度レベルにおける攻撃力を返す。
     * 素手武器種でない場合や定義が無い場合は0を返す。
     */
    public double getUnarmedDamage(String category, int proficiencyLevel) {
        WeaponTypeData data = weaponTypes.get(category);
        if (data == null || !data.unarmed) return 0;
        return data.unarmedBaseDamage + data.unarmedDamagePerLevel * Math.max(0, proficiencyLevel - 1);
    }

    /** この武器種がセットされている間に加算される移動速度ボーナス(%)。定義が無ければ0。 */
    public double getMovementSpeedBonusPercent(String category) {
        WeaponTypeData data = weaponTypes.get(category);
        return data != null ? data.movementSpeedBonusPercent : 0;
    }

    /** 指定した武器種が、解放しないとメニューに出ない制限武器種(体術など)かどうかを返す */
    public boolean requiresUnlock(String category) {
        WeaponTypeData data = weaponTypes.get(category);
        return data != null && data.requiresUnlock;
    }

    /**
     * 素手武器種(体術など)用の、その場限りの仮想SAOWeaponを構築する。
     * 武器アイテムが実在しないため、プレイヤーの熟練度に応じた攻撃力をその都度計算して詰める。
     */
    public sac.plugin.info.SACplugin.item.SAOWeapon buildUnarmedWeapon(Player player, String category) {
        sac.plugin.info.SACplugin.Player.PlayerData stats = plugin.getPlayerManager().getStats(player);
        int profLevel = (stats != null) ? stats.proficiency.getOrDefault(category, 1) : 1;
        double dmg = getUnarmedDamage(category, profLevel);

        sac.plugin.info.SACplugin.item.SAOWeapon fist = new sac.plugin.info.SACplugin.item.SAOWeapon();
        fist.id = "unarmed_" + category;
        fist.name = category;
        fist.category = category;
        fist.material = org.bukkit.Material.AIR;
        fist.range = "Short";
        fist.type = "Strike";
        fist.minAttack = dmg;
        fist.maxAttack = dmg;
        fist.durability = Integer.MAX_VALUE;
        fist.maxDurability = Integer.MAX_VALUE;
        fist.weight = 0;
        fist.requires = 1;
        return fist;
    }

    /**
     * プレイヤーの通常攻撃リーチを指定した武器種のリーチに更新する。
     * 既存のSAO_WEAPON_REACHモディファイアは事前に除去してから追加する。
     */
    public void applyReach(Player player, String category) {
        AttributeInstance ai = player.getAttribute(Attribute.PLAYER_ENTITY_INTERACTION_RANGE);
        if (ai == null) return;

        // 既存のモディファイアを除去
        removeReach(player);

        double reach = getReach(category);
        // ADD_NUMBER: base値(3.0)に加算する形式
        // 目標リーチ - base値 = 加算量
        double base = ai.getBaseValue();
        double addAmount = reach - base;

        try {
            AttributeModifier mod = new AttributeModifier(
                    REACH_UUID, REACH_KEY, addAmount, AttributeModifier.Operation.ADD_NUMBER
            );
            ai.addModifier(mod);
        } catch (IllegalArgumentException e) {
            // 重複の場合は除去して再追加
            removeReach(player);
            try {
                AttributeModifier mod = new AttributeModifier(
                        REACH_UUID, REACH_KEY, addAmount, AttributeModifier.Operation.ADD_NUMBER
                );
                ai.addModifier(mod);
            } catch (Exception ignored) {}
        }
    }

    /**
     * プレイヤーのリーチをデフォルトに戻す（武器を持っていない状態）。
     */
    public void removeReach(Player player) {
        AttributeInstance ai = player.getAttribute(Attribute.PLAYER_ENTITY_INTERACTION_RANGE);
        if (ai == null) return;
        ai.getModifiers().stream()
                .filter(m -> REACH_UUID.equals(m.getUniqueId()) || REACH_KEY.equals(m.getName()))
                .forEach(m -> { try { ai.removeModifier(m); } catch (Exception ignored) {} });
    }
}
