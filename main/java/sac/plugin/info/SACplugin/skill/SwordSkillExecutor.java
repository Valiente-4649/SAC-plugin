package sac.plugin.info.SACplugin.skill;

import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import sac.plugin.info.SACplugin.SACplugin;
import sac.plugin.info.SACplugin.item.SAOWeapon;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SwordSkillExecutor {
    // =====================================================================
    // 3D斬撃（ItemDisplay + CustomModelData）設定
    // =====================================================================
    /** false にすると従来のパーティクル方式のみになる（切り戻し用スイッチ） */
    private static final boolean USE_3D_SLASH = true;
    /** true にすると「計算上の始点・終点・前方」をパーティクルで表示する（向き確認用） */
    private static final boolean DEBUG_SLASH_MARKERS = false;

    // PAPER + CustomModelData 900001..900005 = slash1..slash5
    private static final int SLASH_CMD_BASE = 900000;
    private static final int SLASH_STAGES = 5;
    /** 1段階あたりの表示tick数（5段階 × 1tick = 5tick で完成） */
    private static final int SLASH_TICKS_PER_STAGE = 1;
    /** 完成後に前方へ押し出して消すまでのtick数（5 + 3 = 合計8tick）。2.0ブロック ÷ 3tick ≒ 0.67ブロック/tick（初期の0.5より約1.3倍速い） */
    private static final int SLASH_PUSH_TICKS = 3;
    /** 完成後に前方へ押し出す距離（ブロック）。初期1.5 → 3.0 → 2.0 */
    private static final double SLASH_PUSH_DISTANCE = 2.0;
    /** 斬撃の見た目の中心を置く、プレイヤー前方の水平距離（ブロック） */
    private static final double SLASH_CENTER_FORWARD = 1.85;
    /** 目線より下げる量。水平面を目線の高さに置くと一人称で完全に真横から見え、線にもならないため胸の高さに置く */
    private static final double SLASH_EYE_DROP = 0.5;
    /** 従来(2.7)の3倍（旧4倍=10.8f）。中心補正は scale を含めて計算しているので、大きさを変えても中心はずれない */
    private static final float SLASH_SCALE = 8.1f;

    /** パーティクル版 (spawnLegacySlash) の w / h と同じ値。始点・終点の表を共有するために使う */
    private static final double SLASH_W = 2.8;
    private static final double SLASH_H = 1.3;

    // ---- モデルのローカル座標（slash5 の element from/to の実測範囲） -----------
    // item モデルは座標 (8,8,8) が ItemDisplay の原点になる。
    // このため「見た目の中心」は (範囲の中心 - 8) / 16 ブロックで、原点とは一致しない。
    // 特に Y は 0 付近＝立方体の底面なので、補正しないと SCALE 倍だけ足元へ沈む。
    private static final float MODEL_CENTER_X = (((3.9375f + 9.5625f) / 2.0f) - 8.0f) / 16.0f;
    private static final float MODEL_CENTER_Y = (((-0.025f + 0.0625f) / 2.0f) - 8.0f) / 16.0f;
    private static final float MODEL_CENTER_Z = (((0.625f + 15.0f) / 2.0f) - 8.0f) / 16.0f;

    // ---- モデル軸の割り当て ----
    // [bbmodel の実測]  slash1 は +Z 端(Z=13.2〜15)に現れ -Z 方向へ伸びる / 弧の頂点(膨らみ)は -X 側(X≈3.9)、尖りは +X 側(X≈9)
    //   → 生のモデル軸では「進行方向 = -Z」「前方(膨らみ) = -X」なので、基本符号は両方 -1。
    // [ItemDisplay の仕様]  バニラの ItemDisplay は描画時にモデルを Y軸まわりに180°回してから Transformation を適用する。
    //   つまりモデルの X, Z は Transformation 側から見ると符号が反転する（Y は不変）。
    //   これを ITEMDISPLAY_Y180 で一括管理し、軸の符号と中心オフセットの両方に反映する。
    //   （前回この反転を入れていなかったため、膨らみがプレイヤー側を向き、中心もわずかにずれていた）
    private static final boolean ITEMDISPLAY_Y180 = true;
    private static final float PRE = ITEMDISPLAY_Y180 ? -1.0f : 1.0f;
    // ゲーム内で「斬り始め／斬り終わりの伸びる向き」だけが逆なら MODEL_CHORD_SIGN の符号だけ反転する
    private static final float MODEL_CHORD_SIGN = -1.0f * PRE;
    private static final float MODEL_BULGE_SIGN = -1.0f * PRE;

    // =====================================================================
    // 3D突き（thrust_1〜5）設定
    // =====================================================================
    private static final boolean USE_3D_THRUST = true;
    /** PAPER + CustomModelData 900101..900105 = thrust_1..thrust_5（リソースパック側と合わせること） */
    private static final int THRUST_CMD_BASE = 900100;
    private static final int THRUST_STAGES = 5;
    private static final int THRUST_TICKS_PER_STAGE = 1;
    /** 完成後に前方へ押し出して消すまでのtick数 / 距離（斬撃と同じ挙動） */
    private static final int THRUST_PUSH_TICKS = 2;
    /** 初期3.0 → 4.5 → 3.5。2tickで進むので速さは 1.75ブロック/tick（初期の1.0より約1.75倍速い） */
    private static final double THRUST_PUSH_DISTANCE = 3.5;
    /** 突きの全長（ブロック）。パーティクル版の 4.5 と同じ。大きくしたいときはここを増やす（太さも比例して太くなる） */
    private static final double THRUST_LENGTH = 4.5;
    /** 突きの根元を置く、目線からの前方距離（パーティクル版の 1.0 と同じ） */
    private static final double THRUST_START_FORWARD = 1.0;
    /** 目線より下げる量。真正面の細い線が一人称で点にしか見えないのを避ける */
    private static final double THRUST_EYE_DROP = 0.35;

    // ---- thrust モデルの実測（新モデル thrust1〜5。回転なし・軸平行の立方体のみ）----
    // 槍の形: モデルの X 軸に沿って X=0〜16 まで。細い柄(X=0〜約10, 幅0.125)の先に穂先(X≈10〜16, 最大幅0.75)があり、
    // 尖った先端は +X 端(幅0.125)。軸は Y≈0.03, Z=8 を通る。段階が進むと穂先の左右に羽が増える（全段階で X=0〜16）。
    // → 「尖っている側」はモデルの +X 端。これを相手側（前方）へ向ける。
    private static final float THRUST_ANCHOR_X = 0.0f;   // 後ろ端（柄の端）。これをエンティティ位置に固定する
    private static final float THRUST_AXIS_Y = 0.031f;
    private static final float THRUST_AXIS_Z = 8.0f;
    private static final float THRUST_MODEL_LENGTH = 16.0f;
    private static final float THRUST_SCALE = (float) (THRUST_LENGTH / (THRUST_MODEL_LENGTH / 16.0));
    /** true: モデルの尖った先端(+X)を相手側（前方）に向ける。false: 逆向き */
    private static final boolean THRUST_TIP_FORWARD = true;



    /** 表示中の斬撃（プラグイン無効化時に cleanupSlashDisplays() で一括削除する） */
    private static final Set<ItemDisplay> ACTIVE_SLASH_DISPLAYS = new HashSet<>();

    /** onDisable() から呼ぶと、残っている斬撃エンティティを確実に消せる（任意） */
    public static void cleanupSlashDisplays() {
        for (ItemDisplay d : new ArrayList<>(ACTIVE_SLASH_DISPLAYS)) {
            if (d != null && d.isValid()) d.remove();
        }
        ACTIVE_SLASH_DISPLAYS.clear();
    }

    public static void spawnSlash(Player player, SwordSkillData.SkillSlash slash, String colorStr, boolean isCounter) {
        if (player == null || slash == null || slash.position == null) return;

        // 3D化するのは通常の斬撃と突き(THRUST_)。体術(WAVE_)・抜刀カウンターは従来の演出のまま。
        if (USE_3D_SLASH && !isCounter) {
            try {
                if (spawnModelSlash(player, slash.position, SlashStyle.SKILL)) return;
                if (USE_3D_THRUST && spawnModelThrust(player, slash.position, SlashStyle.SKILL)) return;
            } catch (Exception ex) {
                // 3D表示に失敗してもスキル自体は止めず、パーティクル版へ切り替える
            }
        }
        spawnLegacySlash(player, slash, colorStr, isCounter);
    }

    // =====================================================================
    // 通常攻撃用 API（NormalAttackListener から使う）
    // =====================================================================
    /** 通常攻撃の斬撃に使う白いモデル: slash_white1〜5 = CustomModelData 900201..900205（リソースパック側で用意する） */
    private static final int NORMAL_SLASH_CMD_BASE = 900200;
    /** 通常攻撃の突きに使う白いモデル: thrust_white1〜5 = CustomModelData 900301..900305 */
    private static final int NORMAL_THRUST_CMD_BASE = 900300;
    /** 斬撃モデルの弦（長辺）の長さ。scale=1 のときのブロック数 = (15.0 - 0.625) / 16 */
    private static final double SLASH_MODEL_CHORD = (15.0 - 0.625) / 16.0;
    /** 通常攻撃: 斬撃の見た目の中心を置く位置 = 奥行きリーチ × この比率 */
    private static final double NORMAL_CENTER_RATIO = 0.45;
    /** 通常攻撃: 完成後に前へ押し出す距離 = 奥行きリーチ × この比率 + NORMAL_PUSH_EXTRA */
    private static final double NORMAL_PUSH_RATIO = 0.25;
    /** 通常攻撃: 押し出し距離に足す固定分（ブロック）。1.5 → 0.5 */
    private static final double NORMAL_PUSH_EXTRA = 0.5;
    /** 通常攻撃: 押し出しの速さの倍率（旧: 距離 ÷ 3tick を基準にした何倍か） */
    private static final double NORMAL_PUSH_SPEED_MUL = 1.5;
    /** 通常攻撃の速さの基準にした、旧バージョンの押し出しtick数 */
    private static final int NORMAL_PUSH_BASE_TICKS = 3;

    /** 斬撃・突きの見た目パラメータ。スキルは SKILL、通常攻撃は normalAttackStyle() で作る */
    public static final class SlashStyle {
        final int slashCmdBase, thrustCmdBase;
        /** 1.0 = スキルと同じ大きさ */
        final double sizeMul;
        final double slashCenterForward, slashPush, thrustStartForward, thrustPush;
        /** 押し出しにかけるtick数（距離÷tick数 = 進む速さ） */
        final int slashPushTicks, thrustPushTicks;

        SlashStyle(int slashCmdBase, int thrustCmdBase, double sizeMul,
                   double slashCenterForward, double slashPush, int slashPushTicks,
                   double thrustStartForward, double thrustPush, int thrustPushTicks) {
            this.slashCmdBase = slashCmdBase;
            this.thrustCmdBase = thrustCmdBase;
            this.sizeMul = sizeMul;
            this.slashCenterForward = slashCenterForward;
            this.slashPush = slashPush;
            this.slashPushTicks = slashPushTicks;
            this.thrustStartForward = thrustStartForward;
            this.thrustPush = thrustPush;
            this.thrustPushTicks = thrustPushTicks;
        }

        /** ソードスキル用の見た目 */
        static final SlashStyle SKILL = new SlashStyle(SLASH_CMD_BASE, THRUST_CMD_BASE, 1.0,
                SLASH_CENTER_FORWARD, SLASH_PUSH_DISTANCE, SLASH_PUSH_TICKS,
                THRUST_START_FORWARD, THRUST_PUSH_DISTANCE, THRUST_PUSH_TICKS);
    }

    // =====================================================================
    // 剣筋の名前（position）: 日本語の呼び名 → 内部の英語名 への変換
    // JSONには日本語でも英語でも書ける。英語名（HORIZONTAL_MIDDLE_RIGHT 等）はそのまま使える。
    // 「・」や空白は無視する（「突き・右上」「突き 右上」「突き右上」は同じ）。
    //   横斬り : 上段右 上段左 中段右 中段左 下段右 下段左   （右＝右へ振り抜く / 左＝左へ振り抜く）
    //   斜め   : 右上 左上 右下 左下                         （振り抜く先の方向）
    //   縦     : 斬り上げ(右/左) 斬り下ろし(右/左)           （無印＝中央）
    //   突き   : 中央突き 右突き 左突き 上突き 下突き 右上突き 左上突き 右下突き 左下突き
    //            （「突き右上」のように「突き」を前に書いてもOK）
    //   なし   : なし（見た目を出さない）
    // =====================================================================
    private static final java.util.Map<String, String> POSITION_ALIASES = new java.util.HashMap<>();
    private static final java.util.Map<String, String> THRUST_ALIASES = new java.util.HashMap<>();
    static {
        String[][] rows = {{"上段", "TOP"}, {"中段", "MIDDLE"}, {"下段", "BOTTOM"}};
        for (String[] r : rows) {
            POSITION_ALIASES.put(r[0] + "右", "HORIZONTAL_" + r[1] + "_RIGHT");
            POSITION_ALIASES.put(r[0] + "左", "HORIZONTAL_" + r[1] + "_LEFT");
        }
        POSITION_ALIASES.put("右上", "DIAGONAL_UP_RIGHT");
        POSITION_ALIASES.put("左上", "DIAGONAL_UP_LEFT");
        POSITION_ALIASES.put("右下", "DIAGONAL_RIGHT_DOWN");
        POSITION_ALIASES.put("左下", "DIAGONAL_LEFT_DOWN");
        String[][] vert = {{"斬り上げ", "UP"}, {"斬り下ろし", "DOWN"}};
        for (String[] v : vert) {
            POSITION_ALIASES.put(v[0], v[1] + "_MIDDLE");
            POSITION_ALIASES.put(v[0] + "中央", v[1] + "_MIDDLE");
            POSITION_ALIASES.put(v[0] + "右", v[1] + "_RIGHT");
            POSITION_ALIASES.put(v[0] + "左", v[1] + "_LEFT");
        }
        POSITION_ALIASES.put("なし", "NONE");

        THRUST_ALIASES.put("", "THRUST_");
        THRUST_ALIASES.put("上", "THRUST_UP");
        THRUST_ALIASES.put("下", "THRUST_DOWN");
        THRUST_ALIASES.put("右", "THRUST_RIGHT");
        THRUST_ALIASES.put("左", "THRUST_LEFT");
        THRUST_ALIASES.put("右上", "THRUST_UP_RIGHT");
        THRUST_ALIASES.put("左上", "THRUST_UP_LEFT");
        THRUST_ALIASES.put("右下", "THRUST_DOWN_RIGHT");
        THRUST_ALIASES.put("左下", "THRUST_DOWN_LEFT");
    }

    /** JSONのposition（日本語名・英語名どちらでも）を内部の英語名(大文字)にそろえる。null/空は "" */
    public static String normalizePosition(String raw) {
        if (raw == null) return "";
        final String s = raw.trim();
        if (s.isEmpty()) return "";
        final String key = s.replace("・", "").replace(" ", "").replace("　", "");

        String alias = POSITION_ALIASES.get(key);
        if (alias != null) return alias;
        if (key.contains("突き")) {
            String thrust = THRUST_ALIASES.get(key.replace("突き", "").replace("中央", ""));
            if (thrust != null) return thrust;
        }
        return s.toUpperCase();
    }

    /**
     * 通常攻撃1段分の見た目を、JSONの奥行き(depth)・横幅(width)・高さ(height)に合わせて作る。
     * 斬撃は「長辺 = 横幅（縦斬りは高さ、斜めは対角）」、突きは「全長 = 奥行き」になるよう大きさを決める。
     * @param visualScale 見た目だけの微調整倍率（JSONの visualScale。通常 1.0）
     */
    public static SlashStyle normalAttackStyle(String position, double depth, double width, double height, double visualScale) {
        final String pos = normalizePosition(position);
        final double thrustStart = Math.min(THRUST_START_FORWARD, depth * 0.25);
        double mul;
        if (pos.startsWith("THRUST_")) {
            mul = Math.max(0.1, depth - thrustStart) / THRUST_LENGTH;
        } else {
            double chord;
            if (pos.startsWith("HORIZONTAL_")) chord = width;
            else if (pos.startsWith("UP_") || pos.startsWith("DOWN_")) chord = height;
            else chord = Math.hypot(width, height);
            mul = chord / (SLASH_SCALE * SLASH_MODEL_CHORD);
        }
        mul = Math.max(0.1, mul * visualScale);
        final double oldPush = depth * NORMAL_PUSH_RATIO;
        final double push = oldPush + NORMAL_PUSH_EXTRA;
        // 旧の速さ(oldPush / 3tick)の1.5倍で push を進み切るのに必要なtick数
        final int pushTicks = (int) Math.max(1, Math.round(push / (oldPush / NORMAL_PUSH_BASE_TICKS * NORMAL_PUSH_SPEED_MUL)));
        return new SlashStyle(NORMAL_SLASH_CMD_BASE, NORMAL_THRUST_CMD_BASE, mul,
                depth * NORMAL_CENTER_RATIO, push, pushTicks, thrustStart, push, pushTicks);
    }

    /** 通常攻撃の白い剣筋/突きを飛ばす。position が NONE・未定義、または失敗時は false（何も出さない） */
    public static boolean spawnNormalSlash(Player player, String position, SlashStyle style) {
        if (player == null || position == null || style == null) return false;
        try {
            if (spawnModelSlash(player, position, style)) return true;
            return spawnModelThrust(player, position, style);
        } catch (Exception ex) {
            return false;
        }
    }

    /** JSON の position として有効か（NONE / THRUST_* / 斬撃の各位置） */
    public static boolean isValidEffectPosition(String position) {
        if (position == null) return false;
        final String pos = normalizePosition(position);
        return "NONE".equals(pos) || pos.startsWith("THRUST_") || resolveSlashEndpoints(pos) != null;
    }

    /**
     * 通常攻撃の当たり判定。プレイヤーのヨー角基準の直方体（前方 forward / 後方 backward / 左右 width / 高さ height）。
     * 対象の除外条件は getTargetsInRange と同じ。大きいモブが取りこぼされないよう、対象の横幅の半分だけ判定を甘くしている。
     */
    public static List<LivingEntity> getTargetsInBox(Player player, double forward, double backward, double width, double height) {
        List<LivingEntity> targets = new ArrayList<>();
        final Location origin = player.getLocation();
        final double yawRad = Math.toRadians(origin.getYaw());
        final Vector direction = new Vector(-Math.sin(yawRad), 0, Math.cos(yawRad)); // 斬撃の見た目と同じ向き
        final Vector side = new Vector(-direction.getZ(), 0, direction.getX());

        final double radius = Math.max(forward, Math.max(width, height)) + 2.0;
        for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
            if (!(entity instanceof LivingEntity) || entity.equals(player) || entity.isDead()) continue;
            if (entity instanceof Player || entity instanceof ArmorStand) continue;
            if (entity instanceof Villager) {
                Villager villager = (Villager) entity;
                if (villager.getCustomName() != null) continue;
                boolean isNpc = false;
                for (String tag : villager.getScoreboardTags()) {
                    if (tag.startsWith("sao_shop_") || tag.startsWith("sao_quest_")) { isNpc = true; break; }
                }
                if (isNpc) continue;
            }

            Vector toEntity = entity.getLocation().toVector().subtract(origin.toVector());
            double half = entity.getWidth() / 2.0;
            double f = toEntity.dot(direction);
            double s = Math.abs(toEntity.dot(side));
            double h = Math.abs(entity.getLocation().getY() - origin.getY());

            if (f + half >= -backward && f - half <= forward && s - half <= width / 2.0 && h <= height) {
                targets.add((LivingEntity) entity);
            }
        }
        return targets;
    }

    // =====================================================================
    // 3D斬撃
    //
    // 設計: 「プレイヤーのヨー角だけ」から (right, up, forward) の正規直交基底を作り、
    //       斬撃の始点→終点ベクトルから斬撃面の基底を組み立てて、ItemDisplay の
    //       Transformation(左回転) に直接入れる。
    //       エンティティ自身の yaw/pitch は常に 0 にして、向きの二重適用を排除する。
    //       これにより、どの方角でも (right, up, forward) 座標系での配置・回転が同一になる。
    // =====================================================================
    private static boolean spawnModelSlash(Player player, String position, SlashStyle style) {
        final String pos = normalizePosition(position);
        final double[] ep = resolveSlashEndpoints(pos);
        if (ep == null) return false; // THRUST_ は spawnModelThrust、WAVE_ / 未定義はパーティクル側へ

        Plugin main = org.bukkit.Bukkit.getPluginManager().getPlugin("SACplugin");
        if (main == null) return false;

        final Location eye = player.getEyeLocation();
        final World world = eye.getWorld();
        if (world == null) return false;

        // ---- プレイヤーのローカル基底（ピッチは使わない＝真上/真下を向いても破綻しない） ----
        final double yawRad = Math.toRadians(eye.getYaw());
        final Vector3f fwd = new Vector3f((float) -Math.sin(yawRad), 0f, (float) Math.cos(yawRad));
        final Vector3f side = new Vector3f(-fwd.z, 0f, fwd.x); // legacy と同じ「プレイヤーの右」
        final Vector3f up = new Vector3f(0f, 1f, 0f);

        // ---- 始点・終点（プレイヤー基準の相対ベクトル。legacy の startRel / endRel と同値） ----
        final Vector3f start = new Vector3f(side).mul((float) (ep[0] * style.sizeMul)).add(new Vector3f(up).mul((float) (ep[1] * style.sizeMul)));
        final Vector3f end = new Vector3f(side).mul((float) (ep[2] * style.sizeMul)).add(new Vector3f(up).mul((float) (ep[3] * style.sizeMul)));
        final Vector3f mid = new Vector3f(start).add(end).mul(0.5f);

        final Vector3f chord = new Vector3f(end).sub(start);
        if (chord.lengthSquared() < 1.0e-6f) return false;
        chord.normalize(); // 斬撃の進行方向（水平斬りなら right、斜めなら right+up の傾き）

        // ---- モデル軸 → ワールド軸（回転行列の各列） ----
        // 弦(Z) = 進行方向, 膨らみ(X) = 前方(水平)。前方は弦と常に直交するので、そのまま使える。
        final Vector3f colZ = new Vector3f(chord).mul(MODEL_CHORD_SIGN);
        final Vector3f colX = new Vector3f(fwd).mul(MODEL_BULGE_SIGN);
        final Vector3f colY = new Vector3f(colZ).cross(colX); // 右手系: Y = Z × X（斬撃面の法線 = 厚み方向）

        final Matrix3f basis = new Matrix3f(
                colX.x, colX.y, colX.z,
                colY.x, colY.y, colY.z,
                colZ.x, colZ.y, colZ.z);
        final Quaternionf rotation = new Quaternionf().setFromNormalized(basis);

        // ---- 見た目の中心をエンティティ位置に一致させる平行移動 ----
        // 頂点 v は  T + rotation * (scale * v)  に変換される。
        // モデル中心 c が原点に来る条件:  T = - scale * (rotation * c)
        final float slashScale = SLASH_SCALE * (float) style.sizeMul;
        final Vector3f translation = new Vector3f(MODEL_CENTER_X * PRE, MODEL_CENTER_Y, MODEL_CENTER_Z * PRE)
                .rotate(rotation).mul(-slashScale);
        final Transformation transformation = new Transformation(
                translation, rotation, new Vector3f(slashScale, slashScale, slashScale), new Quaternionf());

        // ---- ワールド座標（発動時点で固定。パーティクル版の origin 固定と同じ挙動） ----
        final double ax = eye.getX() + fwd.x * style.slashCenterForward;
        final double ay = eye.getY() - SLASH_EYE_DROP;
        final double az = eye.getZ() + fwd.z * style.slashCenterForward;
        final Location base = new Location(world, ax + mid.x, ay + mid.y, az + mid.z, 0f, 0f); // yaw/pitch は常に 0
        final Vector3f pushDir = new Vector3f(fwd);

        final ItemDisplay display = world.spawn(base, ItemDisplay.class, d -> {
            d.setItemStack(createSlashItem(style.slashCmdBase, 1));
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setBillboard(Display.Billboard.FIXED);
            d.setTransformation(transformation);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setShadowRadius(0f);
            d.setInvulnerable(true);
            d.setPersistent(false);
            d.setTeleportDuration(1);
        });
        ACTIVE_SLASH_DISPLAYS.add(display);

        final int formTicks = SLASH_STAGES * SLASH_TICKS_PER_STAGE;
        final int totalTicks = formTicks + style.slashPushTicks;

        new BukkitRunnable() {
            int tick = 0;
            int shownStage = 1;

            private void finish() {
                this.cancel();
                ACTIVE_SLASH_DISPLAYS.remove(display);
                if (display.isValid()) display.remove();
            }

            @Override
            public void run() {
                if (!display.isValid() || !player.isOnline()) {
                    finish();
                    return;
                }
                if (tick >= totalTicks) {
                    finish();
                    return;
                }

                // 5段階の切り替え（slash1 → slash5）
                int stage = Math.min(SLASH_STAGES, tick / SLASH_TICKS_PER_STAGE + 1);
                if (stage != shownStage) {
                    shownStage = stage;
                    display.setItemStack(createSlashItem(style.slashCmdBase, stage));
                }

                // 完成後は前方へ少し押し出す（teleportDuration=1 で補間）
                if (tick >= formTicks) {
                    double p = (double) (tick - formTicks + 1) / style.slashPushTicks;
                    Location moved = base.clone().add(
                            pushDir.x * style.slashPush * p, 0.0, pushDir.z * style.slashPush * p);
                    display.teleport(moved);
                }

                if (DEBUG_SLASH_MARKERS) {
                    Location anchor = new Location(world, ax, ay, az);
                    world.spawnParticle(Particle.HAPPY_VILLAGER,
                            anchor.clone().add(start.x, start.y, start.z), 2, 0, 0, 0, 0); // 始点
                    world.spawnParticle(Particle.FLAME,
                            anchor.clone().add(end.x, end.y, end.z), 2, 0, 0, 0, 0);       // 終点
                    world.spawnParticle(Particle.END_ROD,
                            anchor.clone().add(mid.x + fwd.x, mid.y, mid.z + fwd.z), 2, 0, 0, 0, 0); // 前方(膨らみ側)
                }
                tick++;
            }
        }.runTaskTimer(main, 0L, 1L);

        return true;
    }

    private static ItemStack createSlashItem(int cmdBase, int stage) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setCustomModelData(cmdBase + stage);
            item.setItemMeta(meta);
        }
        return item;
    }

    // =====================================================================
    // 3D突き
    //
    // 設計は斬撃と同じ: プレイヤーのヨー角だけから (right, up, forward) を作り、
    //   モデル +Z(突きの進行方向) → forward、モデル Y → up、モデル X → up × forward
    // の回転を Transformation に直接入れる。エンティティの yaw/pitch は常に 0。
    // 突きの「軸上の根元」(THRUST_AXIS_*) をエンティティ位置に一致させるので、
    // 大きさ(THRUST_LENGTH)を変えても根元の位置はずれない。
    // =====================================================================
    private static boolean spawnModelThrust(Player player, String position, SlashStyle style) {
        final String pos = normalizePosition(position);
        if (!pos.startsWith("THRUST_")) return false;

        Plugin main = org.bukkit.Bukkit.getPluginManager().getPlugin("SACplugin");
        if (main == null) return false;

        final Location eye = player.getEyeLocation();
        final World world = eye.getWorld();
        if (world == null) return false;

        // ---- プレイヤーのローカル基底（斬撃・パーティクル版と同じ。ピッチは使わない） ----
        final double yawRad = Math.toRadians(eye.getYaw());
        final Vector3f fwd = new Vector3f((float) -Math.sin(yawRad), 0f, (float) Math.cos(yawRad));
        final Vector3f side = new Vector3f(-fwd.z, 0f, fwd.x); // プレイヤーの右
        final Vector3f up = new Vector3f(0f, 1f, 0f);

        // ---- 突きの位置ずれ（パーティクル版 tx / ty と同値。通常攻撃では sizeMul で縮小） ----
        final double w = SLASH_W * style.sizeMul, h = SLASH_H * style.sizeMul;
        final double tx = pos.contains("LEFT") ? -w * 0.3 : pos.contains("RIGHT") ? w * 0.3 : 0;
        final double ty = pos.contains("UP") ? h * 0.3 : pos.contains("DOWN") ? -h * 0.3 : 0;

        // ---- モデル軸 → ワールド軸 ----
        // 生のモデル軸(右手系 Z = X × Y): X = 突きの進行方向(尖った先端側), Y = up, Z = fwd × up = プレイヤーの右(side)。
        // ItemDisplay の Y180 前回転は PRE で X,Z のみ反転。dirSign = -1 なら進行方向を逆にする。
        final float dirSign = THRUST_TIP_FORWARD ? 1.0f : -1.0f;
        final Vector3f colX = new Vector3f(fwd).mul(PRE * dirSign);
        final Vector3f colY = new Vector3f(up);
        final Vector3f colZ = new Vector3f(fwd).cross(up).mul(PRE * dirSign);

        final Matrix3f basis = new Matrix3f(
                colX.x, colX.y, colX.z,
                colY.x, colY.y, colY.z,
                colZ.x, colZ.y, colZ.z);
        final Quaternionf rotation = new Quaternionf().setFromNormalized(basis);

        // ---- 後ろ端の軸上の点をエンティティ位置に一致させる平行移動:  T = - scale * (rotation * anchor) ----
        final float thrustScale = THRUST_SCALE * (float) style.sizeMul;
        final Vector3f translation = new Vector3f(
                ((THRUST_ANCHOR_X - 8.0f) / 16.0f) * PRE,
                (THRUST_AXIS_Y - 8.0f) / 16.0f,
                ((THRUST_AXIS_Z - 8.0f) / 16.0f) * PRE)
                .rotate(rotation).mul(-thrustScale);
        final Transformation transformation = new Transformation(
                translation, rotation, new Vector3f(thrustScale, thrustScale, thrustScale), new Quaternionf());

        // ---- 後ろ端のワールド座標（発動時に固定。パーティクル版の始点と同じ: 目線の前方 1.0 + 位置ずれ） ----
        final double sx = eye.getX() + fwd.x * style.thrustStartForward + side.x * tx;
        final double sy = eye.getY() + ty - THRUST_EYE_DROP;
        final double sz = eye.getZ() + fwd.z * style.thrustStartForward + side.z * tx;
        final Location base = new Location(world, sx, sy, sz, 0f, 0f); // yaw/pitch は常に 0
        final Vector3f pushDir = new Vector3f(fwd);

        final ItemDisplay display = world.spawn(base, ItemDisplay.class, d -> {
            d.setItemStack(createThrustItem(style.thrustCmdBase, 1));
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setBillboard(Display.Billboard.FIXED);
            d.setTransformation(transformation);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setShadowRadius(0f);
            d.setInvulnerable(true);
            d.setPersistent(false);
            d.setTeleportDuration(1);
        });
        ACTIVE_SLASH_DISPLAYS.add(display);

        final int formTicks = THRUST_STAGES * THRUST_TICKS_PER_STAGE;
        final int totalTicks = formTicks + style.thrustPushTicks;

        new BukkitRunnable() {
            int tick = 0;
            int shownStage = 1;

            private void finish() {
                this.cancel();
                ACTIVE_SLASH_DISPLAYS.remove(display);
                if (display.isValid()) display.remove();
            }

            @Override
            public void run() {
                if (!display.isValid() || !player.isOnline() || tick >= totalTicks) {
                    finish();
                    return;
                }

                // 5段階の切り替え（thrust1 → thrust5）
                int stage = Math.min(THRUST_STAGES, tick / THRUST_TICKS_PER_STAGE + 1);
                if (stage != shownStage) {
                    shownStage = stage;
                    display.setItemStack(createThrustItem(style.thrustCmdBase, stage));
                }

                // 完成後は前方へ押し出す（teleportDuration=1 で補間）
                if (tick >= formTicks) {
                    double p = (double) (tick - formTicks + 1) / style.thrustPushTicks;
                    display.teleport(base.clone().add(
                            pushDir.x * style.thrustPush * p, 0.0, pushDir.z * style.thrustPush * p));
                }

                if (DEBUG_SLASH_MARKERS) {
                    world.spawnParticle(Particle.HAPPY_VILLAGER, base, 2, 0, 0, 0, 0); // 後ろ端
                    world.spawnParticle(Particle.FLAME,
                            base.clone().add(pushDir.x * THRUST_LENGTH * style.sizeMul, 0.0, pushDir.z * THRUST_LENGTH * style.sizeMul), 2, 0, 0, 0, 0); // 先端
                }
                tick++;
            }
        }.runTaskTimer(main, 0L, 1L);

        return true;
    }

    private static ItemStack createThrustItem(int cmdBase, int stage) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setCustomModelData(cmdBase + stage);
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * legacy (spawnLegacySlash) の始点/終点テーブルと同じ内容を (side係数, up係数) で返す。
     * 戻り値: {始点side, 始点up, 終点side, 終点up}（side=プレイヤーの右）。3D化しない位置は null。
     * ※ spawnLegacySlash の switch を変更した場合はこちらも合わせること。
     */
    private static double[] resolveSlashEndpoints(String pos) {
        final double w = SLASH_W, h = SLASH_H;
        switch (pos) {
            case "DIAGONAL_LEFT_DOWN":      return new double[]{ w * 0.8,  h, -w * 0.8, -h};
            case "DIAGONAL_RIGHT_DOWN":     return new double[]{-w * 0.8,  h,  w * 0.8, -h};
            case "HORIZONTAL_TOP_LEFT":     return new double[]{ w,  h, -w,  h};
            case "HORIZONTAL_TOP_RIGHT":    return new double[]{-w,  h,  w,  h};
            case "HORIZONTAL_MIDDLE_LEFT":  return new double[]{ w, 0, -w, 0};
            case "HORIZONTAL_MIDDLE_RIGHT": return new double[]{-w, 0,  w, 0};
            case "HORIZONTAL_BOTTOM_LEFT":  return new double[]{ w, -h, -w, -h};
            case "HORIZONTAL_BOTTOM_RIGHT": return new double[]{-w, -h,  w, -h};
            case "UP_LEFT": case "UP_MIDDLE": case "UP_RIGHT": {
                double o = pos.contains("LEFT") ? -w * 0.2 : pos.contains("RIGHT") ? w * 0.2 : 0;
                return new double[]{o, -h, o, h};
            }
            case "DOWN_LEFT": case "DOWN_MIDDLE": case "DOWN_RIGHT": {
                double o = pos.contains("LEFT") ? -w * 0.2 : pos.contains("RIGHT") ? w * 0.2 : 0;
                return new double[]{o, h, o, -h};
            }
            case "DIAGONAL_UP_LEFT":        return new double[]{ w, -h, -w,  h};
            case "DIAGONAL_UP_RIGHT":       return new double[]{-w, -h,  w,  h};
            default: return null;
        }
    }

    private static void spawnLegacySlash(Player player, SwordSkillData.SkillSlash slash, String colorStr, boolean isCounter) {
        if (slash == null || slash.position == null) return;

        // ★変更: 体術専用エフェクト（WAVE_）。
        // 旧「波動」エフェクトから「紅の砂漠風の格闘エフェクト（突進＋打撃）」に置き換えました。
        if (normalizePosition(slash.position).startsWith("WAVE_")) {
            spawnMartialArtsEffect(player, normalizePosition(slash.position), colorStr);
            return;
        }

        Plugin main = org.bukkit.Bukkit.getPluginManager().getPlugin("SACplugin");
        if (main == null) return;

        final Location origin = player.getEyeLocation();

        // ★【修正3】真下や真上を向いている時にエラーでクラッシュするのを防ぐ
        Vector dir = origin.getDirection().setY(0);
        if (dir.lengthSquared() > 0.0001) {
            dir.normalize();
        } else {
            dir = origin.getDirection(); // 真上・真下の場合は元の向きをそのまま使う
        }

        final Vector direction = origin.getDirection().setY(0).normalize();
        final Vector side = new Vector(-direction.getZ(), 0, direction.getX()).normalize();

        Vector startRel = new Vector(0, 0, 0);
        Vector endRel = new Vector(0, 0, 0);
        boolean isThrust = false;

        double dist = 3.0, w = 2.8, h = 1.3;
        String pos = normalizePosition(slash.position);

        // --- 判定ロジック ---
        switch (pos) {
            case "DIAGONAL_LEFT_DOWN": // 1: ↙ (右上から左下)
                startRel = side.clone().multiply(w * 0.8).add(new Vector(0, h, 0)); // 右上
                endRel = side.clone().multiply(-w * 0.8).add(new Vector(0, -h, 0)); // 左下
                break;
            case "DIAGONAL_RIGHT_DOWN": // 2: ↘ (左上から右下)
                startRel = side.clone().multiply(-w * 0.8).add(new Vector(0, h, 0)); // 左上
                endRel = side.clone().multiply(w * 0.8).add(new Vector(0, -h, 0)); // 右下
                break;
            case "HORIZONTAL_TOP_LEFT": // 上段 ←
                startRel = side.clone().multiply(w).add(new Vector(0, h, 0));
                endRel = side.clone().multiply(-w).add(new Vector(0, h, 0));
                break;
            case "HORIZONTAL_TOP_RIGHT": // 上段 →
                startRel = side.clone().multiply(-w).add(new Vector(0, h, 0));
                endRel = side.clone().multiply(w).add(new Vector(0, h, 0));
                break;
            case "HORIZONTAL_MIDDLE_LEFT": // 中段 ←
                startRel = side.clone().multiply(w).add(new Vector(0, 0, 0));
                endRel = side.clone().multiply(-w).add(new Vector(0, 0, 0));
                break;
            case "HORIZONTAL_MIDDLE_RIGHT": // 中段 →
                startRel = side.clone().multiply(-w).add(new Vector(0, 0, 0));
                endRel = side.clone().multiply(w).add(new Vector(0, 0, 0));
                break;
            case "HORIZONTAL_BOTTOM_LEFT": // 下段 ←
                startRel = side.clone().multiply(w).add(new Vector(0, -h, 0));
                endRel = side.clone().multiply(-w).add(new Vector(0, -h, 0));
                break;
            case "HORIZONTAL_BOTTOM_RIGHT": // 下段 →
                startRel = side.clone().multiply(-w).add(new Vector(0, -h, 0));
                endRel = side.clone().multiply(w).add(new Vector(0, -h, 0));
                break;
            case "UP_LEFT": case "UP_MIDDLE": case "UP_RIGHT":// ↑
                double uOff = pos.contains("LEFT") ? -w*0.2 : pos.contains("RIGHT") ? w*0.2 : 0;
                startRel = side.clone().multiply(uOff).add(new Vector(0, -h, 0));
                endRel = side.clone().multiply(uOff).add(new Vector(0, h, 0));
                break;
            case "DOWN_LEFT": case "DOWN_MIDDLE": case "DOWN_RIGHT":// ↓
                double dOff = pos.contains("LEFT") ? -w*0.2 : pos.contains("RIGHT") ? w*0.2 : 0;
                startRel = side.clone().multiply(dOff).add(new Vector(0, h, 0));
                endRel = side.clone().multiply(dOff).add(new Vector(0, -h, 0));
                break;
            case "DIAGONAL_UP_LEFT": // ↖
                startRel = side.clone().multiply(w).add(new Vector(0, -h, 0));
                endRel = side.clone().multiply(-w).add(new Vector(0, h, 0));
                break;
            case "DIAGONAL_UP_RIGHT": // ↗
                startRel = side.clone().multiply(-w).add(new Vector(0, -h, 0));
                endRel = side.clone().multiply(w).add(new Vector(0, h, 0));
                break;
            default:
                if (pos.startsWith("THRUST_")) {
                    isThrust = true;
                    double tx = pos.contains("LEFT") ? -w*0.3 : pos.contains("RIGHT") ? w*0.3 : 0;
                    double ty = pos.contains("UP") ? h*0.3 : pos.contains("DOWN") ? -h*0.3 : 0;
                    if (pos.contains("UP_RIGHT")) { tx = w*0.3; ty = h*0.3; }
                    else if (pos.contains("UP_LEFT")) { tx = -w*0.3; ty = h*0.3; }
                    else if (pos.contains("DOWN_RIGHT")) { tx = w*0.3; ty = -h*0.3; }
                    else if (pos.contains("DOWN_LEFT")) { tx = -w*0.3; ty = -h*0.3; }
                    startRel = side.clone().multiply(tx).add(new Vector(0, ty, 0));
                }
                break;
        }

        // --- 実行部分 ---
        final Color pColor = parseColor(colorStr);
        final Particle.DustOptions dustOptions = new Particle.DustOptions(pColor, 0.5f);
        final boolean finalIsThrust = isThrust;
        final Vector fStart = startRel, fEnd = endRel;

        new org.bukkit.scheduler.BukkitRunnable() {
            int step = 0;
            // ★短く設定（10 = 約0.5秒）。これで「キレ」のある残像になります。
            final int stayTicks = 10;

            @Override
            public void run() {
                if (step >= stayTicks) {
                    this.cancel();
                    return;
                }

                int points = 100;
                for (int i = 0; i < points; i++) {
                    double t = (double) i / points;
                    org.bukkit.util.Vector finalPos;

                    if (finalIsThrust) {
                        finalPos = direction.clone().multiply(1.0 + t * 4.5).add(fStart);
                    } else {
                        // --- 斬撃：奥行きのある高速な弧 ---
                        // stepごとに一気に前方へ押し出す（0.1 -> 0.4へ加速）
                        double expansion = step * 0.4;

                        // 手前に奥行きを出すために、初期距離(dist)を少し手前(3.0)から開始する設定もアリ
                        double currentDist = (dist - 1.0) + expansion;

                        org.bukkit.util.Vector offset = fStart.clone().add(fEnd.clone().subtract(fStart).multiply(t));

                        // 弧の膨らみ（奥行きの強調）
                        // 1.2 -> 2.5 に上げることで、よりプレイヤー側に突き刺さるような鋭い角度に
                        double curveFactor = Math.sin(t * Math.PI) * 3.0;

                        org.bukkit.util.Vector arcBoost = direction.clone().multiply(curveFactor);

                        finalPos = direction.clone().multiply(currentDist).add(offset).add(arcBoost);
                    }

                    org.bukkit.Location pLoc = origin.clone().add(finalPos);

                    // カウンター時はFLAME、通常はREDSTONE
                    if (isCounter) {
                        player.getWorld().spawnParticle(org.bukkit.Particle.FLAME, pLoc, 1, 0, 0, 0, 0.01);
                    } else {
                        player.getWorld().spawnParticle(Particle.DUST, pLoc, 1, dustOptions);
                        player.getWorld().spawnParticle(Particle.DUST, pLoc, 1, dustOptions);
                    }
                }
                step++;
            }
        }.runTaskTimer(main, 0L, 1L);
    }

    // ==========================================
    // ★変更: 体術専用エフェクト（紅の砂漠風 格闘エフェクト）
    // 旧「WAVE_」のトリガーをそのまま使い、突進＋重厚な打撃エフェクトに置き換えました。
    // ==========================================
    private static void spawnMartialArtsEffect(Player player, String position, String colorStr) {
        Plugin main = org.bukkit.Bukkit.getPluginManager().getPlugin("SACplugin");
        if (main == null) return;

        final Location origin = player.getLocation();
        final Vector direction = origin.getDirection().normalize();

        // WAVE_HIGH / WAVE_MID / WAVE_LOW に応じて打撃エフェクト（爆発）の高さを変える
        final double heightOffset;
        switch (position) {
            case "WAVE_HIGH":
                heightOffset = 1.3;
                break;
            case "WAVE_LOW":
                heightOffset = -1.3;
                break;
            default: // WAVE_MID
                heightOffset = 0.0;
                break;
        }

        // 突進開始の風切り音
        player.playSound(origin, Sound.ENTITY_ENDER_DRAGON_FLAP, 0.8f, 1.8f);

        new BukkitRunnable() {
            int step = 0;
            // エフェクトの継続時間（約0.4秒後に打撃エフェクト）
            final int dashTicks = 8;

            @Override
            public void run() {
                Location loc = player.getLocation();

                // 1. 突進中のエフェクト（足元の土煙と体の周りの風）
                if (step < dashTicks) {
                }
                // 2. 打撃瞬間の重厚なエフェクト
                else if (step == dashTicks) {
                    // 少し前方に打撃判定の見た目をオフセットし、上中下段の高さを反映
                    Location impactLoc = loc.add(direction.clone().multiply(1.5)).add(0, 1.2 + heightOffset, 0);

                    // 重い打撃音と爆発音
                    player.getWorld().playSound(impactLoc, Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 1.2f);
                    player.getWorld().playSound(impactLoc, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 0.7f);

                    // 爆発とドーム状の衝撃波
                    player.getWorld().spawnParticle(Particle.EXPLOSION, impactLoc, 1, 0, 0, 0, 0);

                    // ※バージョンによって SONIC_BOOM が存在しない環境への対策
                    try {
                        player.getWorld().spawnParticle(Particle.valueOf("SONIC_BOOM"), impactLoc.subtract(0, 0.5, 0), 1, 0, 0, 0, 0);
                    } catch (IllegalArgumentException e) {
                        // SONIC_BOOMがない場合は煙で代用
                        player.getWorld().spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, impactLoc, 15, 0.5, 0.5, 0.5, 0.1);
                    }

                    // スキルの色（魔力）を混ぜてダークファンタジー感を強調
                    Color pColor = parseColor(colorStr);
                    Particle.DustOptions dustOptions = new Particle.DustOptions(pColor, 2.0f);
                    player.getWorld().spawnParticle(Particle.DUST, impactLoc, 20, 0.8, 0.8, 0.8, 1, dustOptions);

                    this.cancel();
                    return;
                }
                step++;
            }
        }.runTaskTimer(main, 0L, 1L);
    }

    // ★重要: spawnSlash メソッドの外に配置する
    private static Color parseColor(String str) {
        try {
            if (str != null && str.startsWith("#")) {
                int r = Integer.valueOf(str.substring(1, 3), 16);
                int g = Integer.valueOf(str.substring(3, 5), 16);
                int b = Integer.valueOf(str.substring(5, 7), 16);
                return Color.fromRGB(r, g, b);
            }
        } catch (Exception e) {
            // 解析失敗時はログを出さずデフォルトへ
        }
        return (str != null && str.equalsIgnoreCase("RED")) ? Color.RED : Color.AQUA;
    }

    // SwordSkillExecutor.java に追加
    public static void showRangePreview(Player player, SwordSkillData.SkillRange range) {
        Location origin = player.getLocation();
        Vector dir = origin.getDirection().setY(0).normalize(); // 前方
        Vector side = new Vector(-dir.getZ(), 0, dir.getX()).normalize(); // 左右

        // 四隅の地点を計算
        // 前方の左右、後方の左右
        for (double f = -range.backward; f <= range.forward; f += 0.5) { // 縦の線
            for (double s : new double[]{-range.width / 2.0, range.width / 2.0}) {
                Location dot = origin.clone().add(dir.clone().multiply(f)).add(side.clone().multiply(s));
                player.getWorld().spawnParticle(Particle.DUST, dot.add(0, 0.1, 0), 1, 0, 0, 0, 0, new org.bukkit.Particle.DustOptions(org.bukkit.Color.AQUA, 1));
            }
        }
        for (double s = -range.width / 2.0; s <= range.width / 2.0; s += 0.5) { // 横の線
            for (double f : new double[]{-range.backward, range.forward}) {
                Location dot = origin.clone().add(dir.clone().multiply(f)).add(side.clone().multiply(s));
                player.getWorld().spawnParticle(Particle.DUST, dot.add(0, 0.1, 0), 1, 0, 0, 0, 0, new org.bukkit.Particle.DustOptions(org.bukkit.Color.AQUA, 1));
            }
        }
    }

    public static List<LivingEntity> getTargetsInRange(Player player, SwordSkillData.SkillRange range) {
        List<LivingEntity> targets = new ArrayList<>();
        Location origin = player.getLocation();
        // ★【修正3】ここも同様にエラー対策
        Vector dir = origin.getDirection().setY(0);
        if (dir.lengthSquared() > 0.0001) {
            dir.normalize();
        } else {
            dir = origin.getDirection();
        }
        Vector direction = dir;
        Vector side = new Vector(-direction.getZ(), 0, direction.getX()).normalize(); // 横方向のベクトル

        // 周囲のエフェクト範囲内のエンティティを取得
        double searchRadius = Math.max(range.forward, range.width) + 2;
        for (Entity entity : player.getNearbyEntities(searchRadius, searchRadius, searchRadius)) {
            if (!(entity instanceof LivingEntity) || entity.equals(player)) continue;

            if (entity instanceof Player) continue;

            if (entity instanceof org.bukkit.entity.ArmorStand) continue;

            // ==========================================
            // ★追加: NPC(村人)をスキルのロックオン対象から完全に除外する！
            // ==========================================
            if (entity instanceof org.bukkit.entity.Villager) {
                org.bukkit.entity.Villager villager = (org.bukkit.entity.Villager) entity;
                // 名前付き、またはショップ・クエストタグがあれば完全に無視する
                if (villager.getCustomName() != null) continue;

                boolean isNpc = false;
                for (String tag : villager.getScoreboardTags()) {
                    if (tag.startsWith("sao_shop_") || tag.startsWith("sao_quest_")) {
                        isNpc = true;
                        break;
                    }
                }
                if (isNpc) continue;
            }

            // プレイヤーから敵へのベクトル
            Vector toEntity = entity.getLocation().toVector().subtract(origin.toVector());

            // 各軸への投影（距離計算）
            double forwardDist = toEntity.dot(direction); // 前後の距離
            double sideDist = Math.abs(toEntity.dot(side)); // 左右の幅
            double heightDist = Math.abs(entity.getLocation().getY() - origin.getY()); // 高さの差

            // JSONの範囲内に収まっているか判定
            if (forwardDist >= -range.backward && forwardDist <= range.forward &&
                    sideDist <= (range.width / 2.0) &&
                    heightDist <= range.height) {
                targets.add((LivingEntity) entity);
            }
        }
        return targets;
    }

    // ★変更: 引数に double multiplier を追加
    public static void applySkillDamage(Player player, LivingEntity target, SwordSkillData skill, SAOWeapon weapon, double multiplier) {

        if (target instanceof org.bukkit.entity.Villager) {
            // ...(村人判定のコードはそのまま維持)...
            if (player.getInventory().getItemInMainHand().getType() != org.bukkit.Material.NETHERITE_SWORD) {
                org.bukkit.entity.Villager villager = (org.bukkit.entity.Villager) target;
                if (villager.getCustomName() != null) return; // 名前付きは無敵
                for (String tag : villager.getScoreboardTags()) {
                    if (tag.startsWith("sao_shop_") || tag.startsWith("sao_quest_")) return; // タグ付きも無敵
                }
            }
        }
        // ==========================================
        // ★修正: 実際のダメージ計算にも品質・強化ボーナスを適用する
        // ==========================================
        org.bukkit.inventory.ItemStack item = player.getInventory().getItemInMainHand();

        // ★追加: 旧仕様(単一の強化値)で強化されていた武器を新仕様に一度だけ変換する
        sac.plugin.info.SACplugin.Smithing.ForgeManager.migrateLegacyWeaponIfNeeded(item, weapon);

        double forgeBonus = sac.plugin.info.SACplugin.Event.SAODamageListener.getWeaponBonus(item);
        double upgradeBonus = sac.plugin.info.SACplugin.Event.SAODamageListener.getUpgradeBonus(item);

        // ★変更: skill.damageMultiplier ではなく、渡された multiplier を使う！
        double damage = (weapon.maxAttack + forgeBonus + upgradeBonus) * multiplier;

        // ==========================================
        // ★追加: 会心判定 (DEXによる会心率、ACCによる会心追加ダメージ)。通常攻撃と同じ仕様。
        // ==========================================
        int dexStat = sac.plugin.info.SACplugin.Smithing.ForgeManager.getBonusStat(item, "DEX");
        int accStat = sac.plugin.info.SACplugin.Smithing.ForgeManager.getBonusStat(item, "ACC");
        java.util.Random critRandom = new java.util.Random();
        boolean isCrit = dexStat > 0 && critRandom.nextInt(100) < (dexStat * 2);
        if (isCrit) {
            // ★変更: ACCは1につき「攻撃力の5%」を追加ダメージとして加算する
            damage += damage * accStat * 0.05;
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 1.2f);
        }

        // 2. 無敵時間を強制リセット（1回目から全段ヒットさせるため）
        target.setNoDamageTicks(0);

        // 4. 重要: 0.0ダメージで「攻撃した事実」だけを作る
        // これにより SAODamageListener が finalDamage を再計算するのを防ぎます
        target.damage(0.0, player);

        // 3. 直接HPを削る（これがバニラの防御や他リスナーを無視する）
        double currentHealth = target.getHealth();
        double newHealth = Math.max(0.0, currentHealth - damage);
        target.setHealth(newHealth);

        // ==========================================
        // ★追加: ソードスキルのダメージが入ったので、ここで即座にHPバーを更新！
        // ==========================================
        sac.plugin.info.SACplugin.SACplugin mainPlugin = sac.plugin.info.SACplugin.SACplugin.getPlugin(sac.plugin.info.SACplugin.SACplugin.class);
        if (mainPlugin != null && mainPlugin.getSaoMobManager() != null) {
            mainPlugin.getSaoMobManager().updateHpBar(target);
        }

        // ヒット演出 — 抜刀カウンター中はFLAME、通常スキルはCRIT
        org.bukkit.plugin.Plugin rawPlugin = org.bukkit.Bukkit.getPluginManager().getPlugin("SACplugin");
        sac.plugin.info.SACplugin.SACplugin sacPlugin = (rawPlugin instanceof sac.plugin.info.SACplugin.SACplugin)
                ? (sac.plugin.info.SACplugin.SACplugin) rawPlugin : null;
        boolean isBattouCounter = sacPlugin != null
                && sacPlugin.getSkillListener() != null
                && sacPlugin.getSkillListener().isBattouCounter(player);
        if (isBattouCounter) {
            target.getWorld().spawnParticle(org.bukkit.Particle.FLAME, target.getLocation().add(0, 1, 0), 20, 0.3, 0.5, 0.3, 0.1);
            target.getWorld().spawnParticle(org.bukkit.Particle.LAVA, target.getLocation().add(0, 1, 0), 5, 0.2, 0.3, 0.2, 0);
        } else {
            target.getWorld().spawnParticle(org.bukkit.Particle.CRIT, target.getLocation().add(0, 1, 0), 10);
        }
        target.getWorld().playSound(target.getLocation(), Sound.ENTITY_IRON_GOLEM_HURT, 1.0f, 1.2f);

        // ★ ソードスキルのダメージポップアップ（濃い水色）
        sac.plugin.info.SACplugin.Event.SAODamageListener.showDamagePopup(
                sac.plugin.info.SACplugin.SACplugin.getPlugin(sac.plugin.info.SACplugin.SACplugin.class),
                target, damage, true
        );
    }

    /**
     * JSONのcolor文字列から対応するガラスブロックを返す
     */
    private static org.bukkit.Material getSlashMaterial(String color) {
        if (color == null) return org.bukkit.Material.LIGHT_BLUE_STAINED_GLASS;
        switch (color.toUpperCase()) {
            case "RED": return org.bukkit.Material.RED_STAINED_GLASS;
            case "YELLOW": return org.bukkit.Material.YELLOW_STAINED_GLASS;
            case "PURPLE": return org.bukkit.Material.PURPLE_STAINED_GLASS;
            case "GREEN": return org.bukkit.Material.LIME_STAINED_GLASS;
            default: return org.bukkit.Material.LIGHT_BLUE_STAINED_GLASS;
        }
    }
}