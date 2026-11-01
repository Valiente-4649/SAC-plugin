package sac.plugin.info.SACplugin.skill;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import sac.plugin.info.SACplugin.Event.SAODamageListener;
import sac.plugin.info.SACplugin.Player.PlayerData;
import sac.plugin.info.SACplugin.SACplugin;
import sac.plugin.info.SACplugin.item.SAOWeapon;

import java.security.KeyStore;
import java.util.*;

public class SAOSkillListener implements Listener {
    private final SACplugin plugin;
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();
    private final java.util.Set<UUID> processingPlayers = new java.util.HashSet<>();
    private final Map<UUID, Long> globalCooldowns = new HashMap<>();
    private final SwordSkillCombatManager combatManager;
    // ★追加: 硬直中のプレイヤーを管理するセット
    private final Set<UUID> stiffPlayers = new HashSet<>();
    // ★【追加】システムアシスト中(チャージ〜連撃終了まで)のプレイヤーを管理するセット
    private final Set<UUID> systemAssistPlayers = new HashSet<>();
    // ★追加: スイッチバフを持っているプレイヤーのUUIDリスト
    private final Set<UUID> switchBuffedPlayers = new HashSet<>();

    // ★追加: 短剣5連撃中のプレイヤーUUIDを管理するセット
    // ★追加: 刀のスキルチャージ中プレイヤーを管理するセット
    private final Set<UUID> katanaChargingPlayers = new HashSet<>();
    // ★追加: 抜刀カウンター発動後のスキルデータを保持（発動時にlaunchSkillEffectへ渡す）
    private final Map<UUID, Object[]> pendingCounter = new HashMap<>();
    // ★追加: 刀チャージ中のタスクを管理（カウンター発動時にキャンセルするため）
    private final Map<UUID, org.bukkit.scheduler.BukkitTask> katanaChargeTasks = new HashMap<>();
    // ★追加: 抜刀カウンター発動中フラグ（SwordSkillExecutorでFLAMEパーティクルを出すため）
    private final Set<UUID> battouCounterActive = new HashSet<>();

    public boolean isBattouCounter(Player player) {
        return battouCounterActive.contains(player.getUniqueId());
    }

    public boolean isKatanaCharging(Player player) {
        return katanaChargingPlayers.contains(player.getUniqueId());
    }

    /**
     * 抜刀カウンター発動: チャージを中断し、7倍ダメージでスキルを即発動する
     */
    public void triggerBattouCounter(Player player) {
        UUID uuid = player.getUniqueId();
        if (!katanaChargingPlayers.contains(uuid)) return;

        katanaChargingPlayers.remove(uuid);
        Object[] pending = pendingCounter.remove(uuid);

        // ★ チャージタスクを即キャンセル（通常発動を防ぐ）
        org.bukkit.scheduler.BukkitTask chargeTask = katanaChargeTasks.remove(uuid);
        if (chargeTask != null) chargeTask.cancel();

        if (pending == null) return;

        SwordSkillData skill = (SwordSkillData) pending[0];
        SAOWeapon weapon = (SAOWeapon) pending[1];
        double baseMultiplier = (double) pending[2];

        // 元の倍率に +7.0 する
        double counterMultiplier = baseMultiplier + 7.0;

        player.sendMessage("§b[System] §r§6《 抜刀カウンター 》", "§fダメージ倍率 +7");
        player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_ENDER_DRAGON_FLAP, 1f, 1.8f);
        player.getWorld().spawnParticle(org.bukkit.Particle.FLASH, player.getLocation().add(0, 1, 0), 3, 0.3, 0.3, 0.3, 0);

        // ★ カウンター中フラグON → SwordSkillExecutorでFLAMEパーティクルに切り替わる
        battouCounterActive.add(uuid);
        launchSkillEffect(player, skill, weapon, counterMultiplier, false);
        // ★ 2秒後にフラグOFF（ヒット処理が非同期のため余裕を持たせる）
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> battouCounterActive.remove(uuid), 40L);
    }

    public void addSwitchBuff(UUID uuid) {
        switchBuffedPlayers.add(uuid);
    }
    public boolean consumeSwitchBuff(UUID uuid) {
        return switchBuffedPlayers.remove(uuid); // 持っていれば true を返して同時にリストから消す
    }

    // ★【追加】プレイヤーがサーバーから抜けた時に、クールタイムや硬直をすべてリセットする
    @EventHandler
    public void onPlayerQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        SwordSkillMotionController.stop(event.getPlayer());
        SACCombatFeedback.clear(event.getPlayer());
        cooldowns.remove(uuid);
        globalCooldowns.remove(uuid);
        processingPlayers.remove(uuid);
        stiffPlayers.remove(uuid);
        katanaChargingPlayers.remove(uuid);
        pendingCounter.remove(uuid);
        org.bukkit.scheduler.BukkitTask t = katanaChargeTasks.remove(uuid);
        if (t != null) t.cancel();
        SAODamageListener.rapierComboCount.remove(uuid); // ★追加: ログアウトしたらコンボも消す
    }

    public SAOSkillListener(SACplugin plugin) {
        this.plugin = plugin;
        this.combatManager = new SwordSkillCombatManager(plugin);
    }
    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (stiffPlayers.contains(event.getPlayer().getUniqueId())) {
            // X座標 または Z座標 が変わった場合のみキャンセル
            if (event.getFrom().getX() != event.getTo().getX() || event.getFrom().getZ() != event.getTo().getZ()) {
                Location newLoc = event.getFrom();
                newLoc.setPitch(event.getTo().getPitch()); // 視点の回転は許可
                newLoc.setYaw(event.getTo().getYaw());
                event.setTo(newLoc);
            }
        }
    }

    // ==========================================
    // ★追加: 体術など「素手武器種」の技を、敵を直接狙って右クリックした場合の対応。
    // 敵に照準が合っていると PlayerInteractEvent ではなく PlayerInteractEntityEvent が
    // 発火するため、素手発動の判定がそちらを通っていなかった(＝右クリックが反応しない原因)。
    // ==========================================
    @EventHandler
    public void onEntityRightClick(org.bukkit.event.player.PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        if (!player.isSneaking()) return;
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return; // オフハンド分の重複発火を無視

        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType() != Material.AIR) return; // 武器を持っている場合は通常のonRightClick側で処理される

        PlayerData stats = plugin.getPlayerManager().getStats(player);
        String selectedId = (stats != null) ? stats.selectedSkillId : null;
        SwordSkillData skill = (selectedId != null && !selectedId.isEmpty())
                ? plugin.getSwordSkillManager().getSkill(selectedId) : null;

        if (skill != null && plugin.getWeaponTypeManager().isUnarmed(skill.category)) {
            // バニラの取引画面が開いたりしないようキャンセルする
            event.setCancelled(true);
            executeSkill(player, selectedId, buildUnarmedWeapon(player, skill.category));
        }
    }

    @EventHandler
    public void onRightClick(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        // スニーク + 右クリック判定
        if (!player.isSneaking() || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)) {
            return;
        }

        ItemStack item = player.getInventory().getItemInMainHand();

        // ==========================================
        // ★追加: 体術など「素手武器種」のスキルがセットされていれば、素手のままでも発動を許可する
        // ==========================================
        if (item.getType() == Material.AIR) {
            PlayerData unarmedStats = plugin.getPlayerManager().getStats(player);
            String unarmedSelectedId = (unarmedStats != null) ? unarmedStats.selectedSkillId : null;
            SwordSkillData unarmedSkill = (unarmedSelectedId != null && !unarmedSelectedId.isEmpty())
                    ? plugin.getSwordSkillManager().getSkill(unarmedSelectedId) : null;

            if (unarmedSkill != null && plugin.getWeaponTypeManager().isUnarmed(unarmedSkill.category)) {
                executeSkill(player, unarmedSelectedId, buildUnarmedWeapon(player, unarmedSkill.category));
            }
            return;
        }

        if (!item.hasItemMeta() || !item.getItemMeta().hasLore()) return;

        if (item.getType() == Material.PRISMARINE_SHARD && item.getItemMeta().getDisplayName().equals("§b《Menu》")) {
            return;
        }

        ItemMeta meta = item.getItemMeta();
        // 第一行目のIDから武器データを取得
        String weaponId = org.bukkit.ChatColor.stripColor(meta.getLore().get(0));
        SAOWeapon weapon = plugin.getSaoItemManager().getWeapon(weaponId);

        if (weapon != null) {
            // ★ プレイヤーが現在セットしているスキルIDを取得
            PlayerData stats = plugin.getPlayerManager().getStats(player);
            String selectedId = (stats != null && stats.selectedSkillId != null) ? stats.selectedSkillId : null;

            if (selectedId != null) {
                executeSkill(player, selectedId, weapon);
            } else {
                player.sendMessage("§b[System] §r§cスキルがセットされていません。メニューから選択してください。");
            }
        }
    }
    // ==========================================
    // ★追加: 体術など「素手武器種」用の仮想武器はWeaponTypeManager側で一元的に構築する
    // ==========================================
    private SAOWeapon buildUnarmedWeapon(Player player, String category) {
        return plugin.getWeaponTypeManager().buildUnarmedWeapon(player, category);
    }

    private void executeSkill(Player player, String skillId, SAOWeapon weapon) {
        UUID uuid = player.getUniqueId();
        if (processingPlayers.contains(uuid)) return;

        // ==========================================
        // ★追加: スタン・麻痺中はソードスキルも発動できないようにする
        // ==========================================
        if (plugin.getStatusEffectManager().isActionLocked(player)) {
            return;
        }

        PlayerData stats = plugin.getPlayerManager().getStats(player);
        if (stats != null && stats.level + stats.accReqBonus < weapon.requires) {
            player.sendMessage("§b[System] §r§c要求レベル：§e" + weapon.requires);
            return; // チャージもダメージも発生させずに終了
        }

        SwordSkillData skill = plugin.getSwordSkillManager().getSkill(skillId);
        if (skill == null) return;

        // スキルのカテゴリ（片手剣/細剣）に合わせた熟練度を取得
        int playerProf = stats.proficiency.getOrDefault(skill.category, 1);

        // 必要熟練度に達しているか判定
        if (playerProf < skill.requiredProficiency) {
            player.sendMessage("§c" + skill.category + "の熟練度が足りません");
            player.sendMessage("§b[System] §r§c(必要: " + skill.requiredProficiency + " / 現在: " + playerProf + ")");
            processingPlayers.remove(uuid); // 処理中フラグを解除
            return;
        }

// --- クールタイム判定 ---
        long currentTime = System.currentTimeMillis();

        if (globalCooldowns.containsKey(uuid)) {
            long globalTimeLeft = globalCooldowns.get(uuid) - currentTime;
            if (globalTimeLeft > 0) {
                // 必要ならメッセージを表示（連打対策のため無言でも良いです）
                // player.sendMessage("§cスキル硬直中です...");
                return;
            }
        }

        if (cooldowns.containsKey(uuid) && cooldowns.get(uuid).containsKey(skillId)) {
            long timeLeft = cooldowns.get(uuid).get(skillId) - currentTime;
            if (timeLeft > 0) {
                return;
            }
        }
        processingPlayers.add(uuid);

        // ★【変更】武器タイプ(Thrust等)ではなく、武器カテゴリ(片手剣・細剣・短剣等)で判定する
        if (skill.category != null && weapon.category != null && !skill.category.equals(weapon.category)) {
            player.sendMessage("§b[System] §r§c装備中の武器(" + weapon.category + ")では " + skill.name + " §cを使用できません。");
            processingPlayers.remove(uuid);
            return; // ここで終了させないとチャージが始まってしまう
        }
        // ★【追加】スイッチの判定とスキルの記録
        plugin.getPartyManager().trySwitch(player);

        // ==========================================
        // ★追加: レイピアのコンボボーナス判定とリセット
        // ==========================================
        double currentMultiplier = skill.damageMultiplier; // 元の倍率
        if ("細剣".equals(weapon.category)) {
            int currentRapierCount = SAODamageListener.rapierComboCount.getOrDefault(uuid, 0);
            if (currentRapierCount >= 7) {
                currentMultiplier += 1.0; // ★ボーナス加算！
                player.sendMessage("§a[System] 細剣コンボボーナス　スキル倍率 +1.0");
                player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 2.0f);
            }
        }
        // ==========================================
        // ★追加: スイッチによる威力特大アップバフ！
        // ★変更: 戻り値をisSwitchAttackとして保持し、後の「硬直免除」「ターゲット強制切替」に使う
        // ==========================================
        boolean isSwitchAttack = consumeSwitchBuff(player.getUniqueId());
        if (isSwitchAttack) {
            currentMultiplier += 1.0;
            player.sendMessage("§e[Switch] §aスイッチボーナス スキル倍率 +1");
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_ENDER_DRAGON_FLAP, 1.0f, 1.5f);
        }
        // ★スキルを使用したら（4に達していなくても）必ずカウントを0に戻す
        SAODamageListener.rapierComboCount.put(uuid, 0);

        // ==========================================
        // ★ 順番修正: まず最初にチャージ時間を取得する！
        // ==========================================
        int targetChargeTime = skill.chargeTime > 0 ? skill.chargeTime : 20;

        // ==========================================
        // ★追加: Quicknessボーナス1につきスキル発動時間を0.1秒(2tick)短縮する。
        // 下限は0.5秒(10tick)。
        // ==========================================
        ItemStack heldItemForQuickness = player.getInventory().getItemInMainHand();
        int quicknessBonus = sac.plugin.info.SACplugin.Smithing.ForgeManager.getQuicknessBonus(heldItemForQuickness);
        if (quicknessBonus > 0) {
            int reductionTicks = quicknessBonus * 2; // 0.1秒 = 2tick
            targetChargeTime = Math.max(10, targetChargeTime - reductionTicks);
        }
        // ★修正: 内部クラス(BukkitRunnable)から参照するにはfinal(または事実上final)である
        // 必要があるため、再代入が終わった後の値を別のfinal変数にコピーする。
        final int finalTargetChargeTime = targetChargeTime;

        // --- 1. チャージフェーズ ---
        SwordSkillExecutor.showRangePreview(player, skill.range);

        // ★変更: 発動〜攻撃終了までの硬直は廃止。硬直は攻撃終了後にlaunchSkillEffect側でかける。
        // (旧: applyNewStiffness(player, stiffnessTicks); をここで呼んでいた)

        // チャージ中の水色エフェクトと音
        player.playSound(player.getLocation(), "sao.skill.charge", 5.0f, 1.0f);

        // ★【追加】チャージ開始と同時に持ち替えをロックする
        systemAssistPlayers.add(player.getUniqueId());

        // ==========================================
        // ★修正: タイマーの中で使うために、絶対に変わらないコピー(final)を作る！
        // ==========================================
        final double finalMultiplier = currentMultiplier;

        // ★ 刀なら抜刀カウンター待機状態に登録
        if ("刀".equals(weapon.category)) {
            katanaChargingPlayers.add(uuid);
            pendingCounter.put(uuid, new Object[]{skill, weapon, finalMultiplier});
        }

        org.bukkit.scheduler.BukkitTask chargeTask = new org.bukkit.scheduler.BukkitRunnable() {
            int timer = 0;
            @Override
            public void run() {
                // ★【変更】固定の20ではなく、finalTargetChargeTimeで判定する！
                if (timer >= finalTargetChargeTime) {
                    this.cancel();
                    katanaChargeTasks.remove(uuid);
                    // ★ 通常発動時は抜刀カウンター登録を解除
                    katanaChargingPlayers.remove(uuid);
                    pendingCounter.remove(uuid);
                    // --- 2. スキル発動！ ---
                    // ★修正: 赤くなっていた currentMultiplier を、コピーした finalMultiplier に変える！
                    launchSkillEffect(player, skill, weapon, finalMultiplier, isSwitchAttack);
                    return;
                }

                // 水色のキラキラエフェクト (COLOURED_DUST や AQUA系のパーティクル)
                Location loc = player.getLocation().add(0, 1, 0);

                timer += 2;
            }
        }.runTaskTimer(plugin, 0L, 2L);

        // ★ 刀のチャージタスクを登録（カウンター発動時にキャンセルできるように）
        if ("刀".equals(weapon.category)) {
            katanaChargeTasks.put(uuid, chargeTask);
        }
    }
    // ★変更: 引数に double currentMultiplier を追加
    // ★変更: isSwitchAttack引数を追加(true ならスイッチ攻撃 → 攻撃後硬直を免除し、命中時に敵ターゲットを強制切替する)
    private void launchSkillEffect(Player player, SwordSkillData skill, SAOWeapon weapon, double currentMultiplier, boolean isSwitchAttack) {

        // ★ ソードスキルのシステムアシスト移動。
        // 移動・回転は skill.motion のみで指定。slashes.position から移動を生成しない。
        // 無敵時間は付与しないため、Mobスキルをソードスキル発動だけで無効化することはできない。
        final int motionAttackDelay = SwordSkillMotionController.start(plugin, player, skill);

        // 連撃処理
        new org.bukkit.scheduler.BukkitRunnable() {
            int count = 0;
            @Override
            public void run() {
                if (count >= skill.hits) {
                    this.cancel();

                    UUID uuid = player.getUniqueId();
                    // 5. クールタイム開始（ここで設定すると連撃が終わってから計測される）

                    // ★【追加】連撃が終わった瞬間に、持ち替えロックを解除する！
                    systemAssistPlayers.remove(uuid);

                    // ==========================================
                    // ★変更: 硬直は「攻撃終了後」にここでかける。
                    // 時間 = 単発0.5秒(10tick) + 2連撃目以降は1連撃ごとに0.2秒(4tick)加算。
                    //   単発: 0.5秒 / 2連撃: 0.7秒 / 3連撃: 0.9秒 / 4連撃: 1.1秒 ...
                    // スイッチで発動した攻撃はこの硬直を免除する。
                    // ==========================================
                    if (!isSwitchAttack) {
                        int postAttackStiffnessTicks = 10 + (Math.max(1, skill.hits) - 1) * 4;
                        applyNewStiffness(player, postAttackStiffnessTicks);
                    }

                    setCooldown(player, skill.id, skill.cooldown);
                    globalCooldowns.put(uuid, System.currentTimeMillis() + 1500L);
                    plugin.getPartyManager().recordSkillCast(player);
                    return;
                }

                if (skill.slashes != null && count < skill.slashes.size()) {
                    SwordSkillExecutor.spawnSlash(player, skill.slashes.get(count), skill.color, battouCounterActive.contains(player.getUniqueId()));
                }

                // 3. 範囲内の敵にダメージ
                List<LivingEntity> targets = SwordSkillExecutor.getTargetsInRange(player, skill.range);

                boolean isLastHit = (count == skill.hits - 1);

                ItemStack item = player.getInventory().getItemInMainHand();
                boolean isUnarmedSkill = plugin.getWeaponTypeManager().isUnarmed(weapon.category);

                // ★修正: 体術など「素手武器種」は手が空(ItemMeta無し)のため、従来のitem.hasItemMeta()判定だと
                // ここで丸ごとスキップされてしまい、ダメージが一切発生しなかった。素手武器種なら通す。
                if (isUnarmedSkill || (item != null && item.hasItemMeta())) {
                    ItemMeta meta = (item != null && item.hasItemMeta()) ? item.getItemMeta() : null;

                    for (LivingEntity target : targets) {

                        // ==========================================
                        // ★追加: スイッチで攻撃を当てたプレイヤーに、モブのターゲットを強制的に切り替える
                        // ==========================================
                        if (isSwitchAttack && target instanceof org.bukkit.entity.Mob) {
                            ((org.bukkit.entity.Mob) target).setTarget(player);
                        }

                        // ★変更: 素手武器種は物理アイテムの耐久値を持たないため、熟練度加算のみ行う
                        if (isUnarmedSkill) {
                            SAODamageListener.updateProficiency(player, weapon.category, 1);
                        } else {
                            SAODamageListener.updateDurability(player, item, meta, weapon, 1);
                        }
                        // 斬撃音 (複数回重なると「カカッ」という音になる)
                        player.playSound(player.getLocation(), "sao.skill.attack", 5.0f, 1.0f);
                        if (isLastHit) {
                            // フィニッシュ時の重い音
                            player.getWorld().playSound(target.getLocation(), Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 0.4f, 0.8f);
                        } else {
                            player.getWorld().playSound(target.getLocation(), Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, 0.2f, 1.5f);
                        }

                        // 切るエフェクト — 抜刀カウンター中はFLAME
                        if (battouCounterActive.contains(player.getUniqueId())) {
                            player.getWorld().spawnParticle(org.bukkit.Particle.FLAME, target.getLocation().add(0, 1, 0), 8, 0.2, 0.3, 0.2, 0.05);
                        } else {
                            player.getWorld().spawnParticle(org.bukkit.Particle.SWEEP_ATTACK, target.getLocation().add(0, 1, 0), 1);
                        }

                        // ★追加: 旧仕様(単一の強化値)で強化されていた武器を新仕様に一度だけ変換する
                        sac.plugin.info.SACplugin.Smithing.ForgeManager.migrateLegacyWeaponIfNeeded(item, weapon);

                        // ダメージ計算とアクションバー表示
                        double forgeBonus = SAODamageListener.getWeaponBonus(item);
                        double upgradeBonus = SAODamageListener.getUpgradeBonus(item);

                        // ★変更: skill.damageMultiplier ではなく currentMultiplier を使う！
                        double damage = (weapon.maxAttack + forgeBonus + upgradeBonus) * currentMultiplier;
                        int finalDamage = (int) Math.round(damage);

// ご希望の形式: 《 Vertical 80 》
                        player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                                new net.md_5.bungee.api.chat.TextComponent("§b《 §b" + skill.name + " 》"));

                        combatManager.applySkillHit(player, target, isLastHit);
                        SACCombatFeedback.skillHit(player, target, isLastHit, battouCounterActive.contains(player.getUniqueId()));

                        // ★変更: 実際のダメージ処理にも currentMultiplier を渡す
                        SwordSkillExecutor.applySkillDamage(player, target, skill, weapon, currentMultiplier);
                    }
                }
                count++;
            }
        }.runTaskTimer(plugin, motionAttackDelay, 5L); // モーション開始後、設定されたtickだけ待って攻撃判定
    }
    private void setCooldown(Player player, String skillId, int cooldownSeconds) {
        UUID uuid = player.getUniqueId();
        long expireTime = System.currentTimeMillis() + (cooldownSeconds * 1000L);

        Map<String, Long> playerCooldowns = cooldowns.computeIfAbsent(uuid, k -> new HashMap<>());
        playerCooldowns.put(skillId, expireTime);

        player.sendMessage("§7[System] スキルクールタイム開始 (" + cooldownSeconds + "秒)");

        // クールタイム終了時に「使用可能」と出すタイマー
        new org.bukkit.scheduler.BukkitRunnable() {
            @Override
            public void run() {
                // ★【修正2】プレイヤーがオフラインでも「処理中ロック」は絶対に解除する！
                processingPlayers.remove(uuid);

                // メッセージ送信のために最新のプレイヤー状態を取得する
                Player onlinePlayer = org.bukkit.Bukkit.getPlayer(uuid);
                if (onlinePlayer != null && onlinePlayer.isOnline()) {
                    SwordSkillData skill = plugin.getSwordSkillManager().getSkill(skillId);
                    String skillName = (skill != null) ? skill.name : skillId;

                    // 終了通知
                    onlinePlayer.sendMessage("§a[READY] " + skillName + " §fが使用可能になりました");
                    onlinePlayer.playSound(onlinePlayer.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 2.0f);

                    // アクションバーにも出す
                    onlinePlayer.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                            new net.md_5.bungee.api.chat.TextComponent("§a" + skillName + " READY"));
                }
            }
        }.runTaskLater(plugin, cooldownSeconds * 20L); // 秒をTickに変換
    }

    public boolean isProcessing(Player player) {
        return processingPlayers.contains(player.getUniqueId());
    }

    private void applyNewStiffness(Player player, int ticks) {
        // 1. ノックバック耐性のみ Attribute で操作
        org.bukkit.attribute.AttributeInstance kbRes = player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_KNOCKBACK_RESISTANCE);
        org.bukkit.attribute.AttributeModifier kbMod = new org.bukkit.attribute.AttributeModifier(
                java.util.UUID.fromString("00000000-0000-0000-0000-000000000002"), "SAO_AntiKB", 1.0,
                org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER);

        if (kbRes != null) {
            // ★【修正1】すでに同じIDのモディファイアが残っていたらエラーになる前に消す！
            for (org.bukkit.attribute.AttributeModifier mod : kbRes.getModifiers()) {
                if (mod.getUniqueId().equals(kbMod.getUniqueId())) {
                    kbRes.removeModifier(mod);
                }
            }
            kbRes.addModifier(kbMod);
        }

        // 2. 移動停止はリストへの登録で行う (onPlayerMoveで制御)
        UUID uuid = player.getUniqueId();
        stiffPlayers.add(uuid);

        // 3. 指定時間後に解除
        new org.bukkit.scheduler.BukkitRunnable() {
            @Override
            public void run() {
                if (kbRes != null) {
                    // ★ここでも安全のためにIDチェックして消す
                    for (org.bukkit.attribute.AttributeModifier mod : kbRes.getModifiers()) {
                        if (mod.getUniqueId().equals(kbMod.getUniqueId())) {
                            kbRes.removeModifier(mod);
                        }
                    }
                }
                stiffPlayers.remove(uuid);
            }
        }.runTaskLater(plugin, ticks);
    }
    // ★【追加】スイッチ発動時にクールタイムを完全にカットするメソッド
    public void resetCooldown(Player player) {
        UUID uuid = player.getUniqueId();
        cooldowns.remove(uuid);
        globalCooldowns.remove(uuid);

        processingPlayers.remove(uuid);
        stiffPlayers.remove(uuid);

        player.sendMessage("§b[System] §r§b【Switch】 §aスイッチ発動　クールタイムがリセットされました");
    }

    // ==========================================
    // ★ システムアシスト中の武器固定（持ち替え不可）処理
    // ==========================================

    // 1. ホットバーの選択切り替え(1~9キーやホイール)をロック
    @EventHandler
    public void onItemHeldChange(org.bukkit.event.player.PlayerItemHeldEvent event) {
        if (systemAssistPlayers.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    // 2. アイテムを落とす(Qキー)をロック
    @EventHandler
    public void onItemDrop(org.bukkit.event.player.PlayerDropItemEvent event) {
        if (systemAssistPlayers.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    // 3. オフハンドへの切り替え(Fキー)をロック
    @EventHandler
    public void onOffhandSwap(org.bukkit.event.player.PlayerSwapHandItemsEvent event) {
        if (systemAssistPlayers.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    // 4. インベントリを開いて直接移動させるのをロック
    @EventHandler
    public void onInventoryClick(org.bukkit.event.inventory.InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player) {
            if (systemAssistPlayers.contains(event.getWhoClicked().getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }

    // 5. 【追加】スキル中にログアウトしてしまった場合のバグ防止
    @EventHandler
    public void onPlayerQuitClearLock(org.bukkit.event.player.PlayerQuitEvent event) {
        systemAssistPlayers.remove(event.getPlayer().getUniqueId());
    }
}