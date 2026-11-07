package sac.plugin.info.SACplugin.skill;

import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import sac.plugin.info.SACplugin.SACplugin;
import sac.plugin.info.SACplugin.item.SAOWeapon;

import java.util.*;

public class ParryManager implements Listener {
    private final SACplugin plugin;
    // クールタイム管理用 (UUID -> 最後に「失敗した」パリィの時間)
    private final Map<UUID, Long> parryCooldowns = new HashMap<>();
    // 現在パリィ受付中のプレイヤーリスト
    private final Set<UUID> activeParryWindows = new HashSet<>();
    // ★追加: 今回の受付時間中に1回でも成功したかどうか(成功していればクールタイム無しにするため)
    private final Set<UUID> parrySucceededThisWindow = new HashSet<>();

    // ★変更: 受付tickを4→3に短縮
    private static final long PARRY_WINDOW_TICKS = 3L;
    private static final long PARRY_COOLDOWN_MS = 5000L;
    // ==========================================
    // ★ジャストパリィ: パリィ受付の開始から、モブの攻撃判定までの時間がこれ以内ならジャスト。
    // 見た目(爆発)→判定は1tick=50msなので、60ms=「見た目が出てから押した」場合だけジャストになる。
    // (通常パリィの受付は3tick=150msあるので、100msだと受付のほぼ全域がジャストになってしまっていた)
    // モブ特殊攻撃は「見た目」が判定の1tick(0.05秒)前に出るので、見た目を基準に前後0.05秒ずつが判定窓になる。
    // ==========================================
    public static final long JUST_PARRY_WINDOW_MS = 60L;
    // パリィ受付が「開始」した時刻(nanoTime)。連打で更新されない(受付開始の1回目だけを記録)。
    private final Map<UUID, Long> parryWindowStartNanos = new HashMap<>();
    // ==========================================
    // ★変更: パリィ成功時のモブの拘束は、AI停止(setAI)や移動速度の書き換えをやめ、
    // 「強力な鈍足(ポーション効果)」で行う。効果はサーバー側が秒数で自動的に解除するので、
    // こちらで「元に戻す処理」が要らず、戻し忘れ・記憶違いによる永久停止が起こらない。
    // ==========================================
    private static final int PARRY_SLOW_SECONDS = 5;      // 鈍足の持続時間(秒)
    private static final int PARRY_SLOW_AMPLIFIER = 6;    // 鈍足VII相当(ほぼ完全に動けない)

    // 現在パリィによる鈍足中のモブ(UUID -> タグ解除タスク)。
    // ここに入っている間は、新たなパリィによる鈍足・時間延長を受け付けない。
    private final Map<UUID, org.bukkit.scheduler.BukkitTask> parriedMobs = new HashMap<>();

    public ParryManager(SACplugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onRightClick(PlayerInteractEvent event) {
        // 右クリック以外は無視
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();

        // ★ スキル発動(しゃがみ)と混ざらないように、しゃがみ中はパリィしない
        if (player.isSneaking()) return;

        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || !item.hasItemMeta() || !item.getItemMeta().hasLore()) return;

        String weaponId = org.bukkit.ChatColor.stripColor(item.getItemMeta().getLore().get(0));
        SAOWeapon weapon = plugin.getSaoItemManager().getWeapon(weaponId);

        // ==========================================
        // ★変更: 武器種のハードコード判定をやめ、weapon_types.jsonの"canParry"フィールドで管理する
        // ==========================================
        if (weapon == null || !plugin.getWeaponTypeManager().canParry(weapon.category)) return;
        // ★ パリィ不可の武器種(刀・細剣・短剣・槍など)は抜刀カウンター等の代替手段を使う

        UUID uuid = player.getUniqueId();
        long currentTime = System.currentTimeMillis();
        long lastFail = parryCooldowns.getOrDefault(uuid, 0L);

        // ★ 5秒(5000ミリ秒)のクールタイム判定(失敗時のみ課される)
        if (currentTime - lastFail < PARRY_COOLDOWN_MS) {
            return; // クールタイム中は何も起きない
        }

        // パリィ発動！
        // ★ジャストパリィ判定用: 受付が新しく始まった時だけ開始時刻を記録する(連打でジャストを狙えないように)
        if (activeParryWindows.add(uuid)) {
            parryWindowStartNanos.put(uuid, System.nanoTime());
        }
        parrySucceededThisWindow.remove(uuid); // 今回の受付の成功記録をリセット

        // 構えた時の「チャキッ」という音
        player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_IRON, 1.0f, 2.0f);

        // ★受付時間終了後、成功していなければここでクールタイムを課す。
        // 成功していればクールタイム無し(＝連続して他のモブの攻撃もパリィできる)。
        new BukkitRunnable() {
            @Override
            public void run() {
                activeParryWindows.remove(uuid);
                parryWindowStartNanos.remove(uuid);
                if (!parrySucceededThisWindow.remove(uuid)) {
                    parryCooldowns.put(uuid, System.currentTimeMillis());
                }
            }
        }.runTaskLater(plugin, PARRY_WINDOW_TICKS);
    }

    // ダメージリスナーから「今パリィ中か？」を確認するためのメソッド
    public boolean isParrying(Player player) {
        return activeParryWindows.contains(player.getUniqueId());
    }

    // ==========================================
    // ★変更: パリィ成功時にダメージリスナー側から呼び出す。
    // 従来のremoveParry()と違い受付時間自体は終了させない
    // (同じ受付時間内に複数体のモブの攻撃をそれぞれパリィできるようにするため)。
    // 成功フラグを記録し、攻撃してきたモブを1秒間硬直させる。
    // ==========================================
    public void onParrySuccess(Player player, LivingEntity attacker) {
        parrySucceededThisWindow.add(player.getUniqueId());
        freezeMob(attacker);
        SACCombatFeedback.parrySuccess(player, attacker);
    }

    /**
     * パリィ成功時に、攻撃してきたモブ(通常モブ・ボス問わず)に強力な鈍足を付与する。
     * あわせて「sao_parried」タグを付け、FloorBossAI/SAOMobSkillExecutor側で
     * 特殊攻撃を使わせないようにする(従来通り)。タグは鈍足と同じ秒数で自動的に外れる。
     * 鈍足中は新たなパリィによる鈍足・延長を受け付けない。
     */
    private void freezeMob(LivingEntity mob) {
        if (mob == null || !mob.isValid()) return;

        UUID mobUuid = mob.getUniqueId();
        // 既にパリィによる鈍足中なら、新たなパリィは受け付けない(時間の延長もしない)
        if (parriedMobs.containsKey(mobUuid)) return;

        mob.addScoreboardTag("sao_parried");
        mob.addPotionEffect(new org.bukkit.potion.PotionEffect(
                org.bukkit.potion.PotionEffectType.SLOWNESS, PARRY_SLOW_SECONDS * 20, PARRY_SLOW_AMPLIFIER, false, true, false));

        org.bukkit.scheduler.BukkitTask releaseTask = org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
            parriedMobs.remove(mobUuid);
            // チャンクの読み込み直し等でエンティティ参照が無効になっていても外せるよう、UUIDから再取得する
            org.bukkit.entity.Entity target = mob.isValid() ? mob : org.bukkit.Bukkit.getEntity(mobUuid);
            if (target != null) target.removeScoreboardTag("sao_parried");
        }, PARRY_SLOW_SECONDS * 20L);

        parriedMobs.put(mobUuid, releaseTask);
    }

    // ==========================================
    // ★ジャストパリィ
    // ==========================================

    /** 今の受付が、JUST_PARRY_WINDOW_MS(0.1秒)以内に開始されたものか。モブの攻撃判定の瞬間に呼ぶ。 */
    public boolean isJustParry(Player player) {
        UUID uuid = player.getUniqueId();
        if (!activeParryWindows.contains(uuid)) return false;
        Long start = parryWindowStartNanos.get(uuid);
        if (start == null) return false;
        return (System.nanoTime() - start) <= JUST_PARRY_WINDOW_MS * 1_000_000L;
    }

    /**
     * ジャストパリィ成功時にモブ特殊攻撃側から呼ぶ。
     * 受付は成功扱い(クールタイム無し)にして、円状のキラキラ演出を出し、円が最大になった瞬間(0.15秒後)に
     * 従来のパリィと同じ「吹っ飛ばし + 硬直」と、特殊攻撃ダメージの等倍反射を行う。
     *
     * @param reflectDamage モブへ返すダメージ（モブの特殊攻撃ダメージそのもの。プレイヤーの防御力は引かない）
     */
    public void onJustParrySuccess(Player player, LivingEntity attacker, double reflectDamage) {
        parrySucceededThisWindow.add(player.getUniqueId());
        if (attacker == null || !attacker.isValid()) return;

        JustParryEffect.play(plugin, player, attacker, () -> {
            if (!player.isOnline() || !attacker.isValid() || attacker.isDead()) return;

            // 従来のパリィと同じ吹っ飛ばし
            Vector kb = attacker.getLocation().toVector().subtract(player.getLocation().toVector());
            if (kb.lengthSquared() < 1.0E-6) kb = player.getLocation().getDirection();
            kb.normalize().multiply(1.5).setY(0.4);
            attacker.setVelocity(kb);

            // 従来のパリィと同じ硬直(強力な鈍足 + sao_parriedタグ)と火花
            freezeMob(attacker);
            SACCombatFeedback.parrySuccess(player, attacker);

            // 特殊攻撃ダメージを等倍で返す(最後に行う: 倒せた場合もあるため)
            reflectDamage(player, attacker, reflectDamage);
        });
    }

    /** ソードスキルと同じ方式(無敵時間リセット → damage(0.0) で攻撃者を記録 → 直接HPを削る)でモブにダメージを返す */
    private void reflectDamage(Player player, LivingEntity mob, double damage) {
        if (damage <= 0 || mob == null || !mob.isValid() || mob.isDead()) return;

        mob.setNoDamageTicks(0);
        mob.damage(0.0, player);
        mob.setHealth(Math.max(0.0, mob.getHealth() - damage));
        if (plugin.getSaoMobManager() != null) {
            plugin.getSaoMobManager().updateHpBar(mob);
        }
        mob.getWorld().spawnParticle(org.bukkit.Particle.CRIT, mob.getLocation().add(0, mob.getHeight() / 2.0, 0), 20, 0.3, 0.4, 0.3, 0.3);
        sac.plugin.info.SACplugin.Event.SAODamageListener.showDamagePopup(plugin, mob, damage, true);
    }

    // ★後方互換用に残す(旧仕様の呼び出し元がまだ残っている場合に備えて)。
    // 新仕様では受付時間を終了させず、代わりにonParrySuccess()を使うこと。
    @Deprecated
    public void removeParry(Player player) {
        parrySucceededThisWindow.add(player.getUniqueId());
    }
}