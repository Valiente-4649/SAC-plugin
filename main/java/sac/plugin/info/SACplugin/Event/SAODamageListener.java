package sac.plugin.info.SACplugin.Event;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import sac.plugin.info.SACplugin.Player.PlayerData;
import sac.plugin.info.SACplugin.SACplugin;
import sac.plugin.info.SACplugin.item.SAOWeapon;
import sac.plugin.info.SACplugin.mob.SAOMobData;
import org.bukkit.entity.LivingEntity;

import java.util.List;
import java.util.Random;

public class SAODamageListener implements Listener {
    private final SACplugin plugin;
    private final Random random = new Random();

    // ★追加: レイピア(細剣)の連続ヒット数を記録する
    public static final java.util.Map<java.util.UUID, Integer> rapierComboCount = new java.util.HashMap<>();

    // ==========================================
    // ★新規追加: 武器の攻撃ヒット数をカウントして耐久値を管理する
    // ==========================================
    public static final java.util.Map<java.util.UUID, Integer> weaponHitCounter = new java.util.HashMap<>();

    // ==========================================
    // ★新規追加: 防具の被弾数をカウントして耐久値を管理する
    // ==========================================
    public static final java.util.Map<java.util.UUID, Integer> armorHitCounter = new java.util.HashMap<>();

    // ★追加: 短剣コンボ発動中フラグ
    public static final java.util.Set<java.util.UUID> daggerComboActive = new java.util.HashSet<>();

    // ★追加: SAOモブの通常攻撃クールタイム管理 (モブUUID → 次に攻撃可能になる時刻のミリ秒)
    public static final java.util.Map<java.util.UUID, Long> mobAttackCooldowns = new java.util.HashMap<>();
    // ==========================================
    // ★追加: バニラのgetKiller()に頼らない、確実な「最後にHPを削ったプレイヤー」の記録。
    // このプラグインはevent.setDamage()を使わずtargetMob.setHealth()で手動にダメージを
    // 反映しているため、バニラ内部の「最後にプレイヤーに殴られた」記録が0ダメージ扱いで
    // 更新されず、連続で敵を倒すとentity.getKiller()がnullを返すことがある。
    // その対策として、実際にHPを減らした瞬間に必ずここへ記録する。
    // ==========================================
    public static final java.util.Map<java.util.UUID, java.util.UUID> lastDamagerMap = new java.util.HashMap<>();
    private static final long MOB_ATTACK_COOLDOWN_MS = 1000L; // 1秒

    public SAODamageListener(SACplugin plugin) {
        this.plugin = plugin;
    }

    // ★追加: 武器のLoreから「(+〇)」の強化値を読み取るメソッド
    public static double getWeaponBonus(ItemStack item) {
        if (item == null || !item.hasItemMeta() || !item.getItemMeta().hasLore()) return 0.0;
        for (String line : item.getItemMeta().getLore()) {
            String stripped = org.bukkit.ChatColor.stripColor(line);
            // ForgeManagerで設定した "Attack: 12 - 22 (+2)" の形から数字を抽出
            if (stripped.contains("Attack:") && stripped.contains("(+")) {
                try {
                    String bonusStr = stripped.substring(stripped.indexOf("(+") + 2, stripped.indexOf(")"));
                    return Double.parseDouble(bonusStr);
                } catch (Exception e) {
                }
            }
        }
        return 0.0;
    }

    // ★変更: 強化システムがSTR/DUR/DEX/ACC/WGTのボーナスステータス方式に統一されたため、
    // 旧UpgradeLevel由来の追加ボーナスは廃止(常に0を返す)。STRによる攻撃力ボーナスは
    // Attackの "(+N)" 表記に既に含まれており、getWeaponBonus()側で正しく読み取られる。
    public static double getUpgradeBonus(ItemStack item) {
        return 0.0;
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
    public void onPlayerDamage(org.bukkit.event.entity.EntityDamageByEntityEvent event) {

        // --- 1. プレイヤーがダメージを受けたかチェック ---
        if (!(event.getEntity() instanceof Player)) return;

        Player player = (Player) event.getEntity();
        // チュートリアルのボアからの攻撃のみHPダメージを無効化する。
        // AIの追跡・攻撃自体は止めない。バニラ無敵フラグも使用しない。
        if (sac.plugin.info.SACplugin.Tutorial.TutorialFlowListener
                .isProtectedFromTutorialBoar(player, event.getDamager())) {
            event.setCancelled(true);
            event.setDamage(0);
            return;
        }
        PlayerData stats = plugin.getPlayerManager().getStats(player);
        if (stats == null) return;

        // ==========================================
        // ★追加: setInvulnerable(true)(チュートリアル等)中は
        // カスタムダメージ処理自体を完全にスキップする。
        // (通常のEntityDamageEventはバニラ側で自動的にキャンセルされるが、
        // このリスナーはignoreCancelled指定が無く、キャンセル済みでも実行されてしまうため、
        // stats.currentHPへの直接減算などバニラ判定を素通りする処理を明示的に止める)
        // ==========================================
        if (player.isInvulnerable()) {
            event.setCancelled(true);
            return;
        }

        // ==========================================
        // ★追加: 被弾のたびに、自分が装備している防具のarmors.json基礎値が
        // 変わっていないかチェックする(強化台を開かなくても自動的に引き継がれる)。
        // 変化があった防具が1つでもあれば、stats.defense等を再計算する。
        // ==========================================
        boolean anyArmorMigrated = false;
        for (org.bukkit.inventory.ItemStack armorPiece : player.getInventory().getArmorContents()) {
            if (armorPiece == null || armorPiece.getType() == org.bukkit.Material.AIR
                    || !armorPiece.hasItemMeta() || !armorPiece.getItemMeta().hasLore()) continue;
            String armorIdLine = org.bukkit.ChatColor.stripColor(armorPiece.getItemMeta().getLore().get(0));
            sac.plugin.info.SACplugin.item.SAOArmor armorBase = plugin.getSaoItemManager().getArmor(armorIdLine);
            if (armorBase == null) continue;
            if (sac.plugin.info.SACplugin.Smithing.ArmorForgeManager.migrateArmorSchemeIfNeeded(armorPiece, armorBase)) {
                anyArmorMigrated = true;
            }
        }
        if (anyArmorMigrated) {
            plugin.getPlayerManager().syncHealth(player, stats);
            player.sendMessage("§e[System] §f装備中の防具の仕様が更新されたため、割り振り済みステータスをリセットし、未割り振りポイントとして引き継ぎました。強化メニューから振り直してください。");
        }

        // ==========================================
        // ★修正: 0.01 (特殊攻撃の被弾モーション用) も無視するように変更！
        // ==========================================
        if (event.getDamage() <= 0.01) return;

        int mobLevel = 1;
        double mobBaseDamage = 0; // JSONの数値を格納する変数
        boolean isSaoMob = false;

        // --- 2. 敵のレベルを取得 ---
        for (String tag : event.getDamager().getScoreboardTags()) {
            if (tag.startsWith("sao_mob_")) {
                String mobId = tag.replace("sao_mob_", "");
                SAOMobData mobData = plugin.getSaoMobManager().getMobData(mobId);
                if (mobData != null) {
                    mobLevel = mobData.level;
                    mobBaseDamage = mobData.attackDamage; // JSONの攻撃力を取得
                    isSaoMob = true;
                }
                break;
            }
        }

        // ==========================================
        // ★追加: SAOモブの通常攻撃に1秒のクールタイムを設ける
        // バニラのMeleeAttackGoalにはクールタイムがなく連続ヒットしてしまうため、
        // モブ個体(UUID)ごとに最後の攻撃時刻を記録し、1秒以内の再攻撃は無効化する。
        // 特殊攻撃(SAOMobSkillExecutor経由)はこの判定の対象外。
        // ==========================================
        if (isSaoMob) {
            java.util.UUID mobUuid = event.getDamager().getUniqueId();
            long now = System.currentTimeMillis();
            long nextAllowed = mobAttackCooldowns.getOrDefault(mobUuid, 0L);
            if (now < nextAllowed) {
                event.setCancelled(true);
                return;
            }
            mobAttackCooldowns.put(mobUuid, now + MOB_ATTACK_COOLDOWN_MS);
        }

        // ==========================================
        // ★変更: 通常攻撃はパリィ不可にする(特殊攻撃のみパリィ可能)。
        // 特殊攻撃のパリィ判定はSAOMobSkillExecutor.java側に残っている。
        // ==========================================

        // --- 3. 回避判定 (AGIシステム) ---
        // ★変更: 防具のAGIボーナス(金属防具鍛造)も合算する
        if (mobLevel <= ((stats.agi + stats.armorAgiBonus) * 2)) {
            if (random.nextDouble() < 0.30) { // 30%の確率で回避
                event.setCancelled(true);

                player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 2.0f);
                player.sendMessage("§7[System] §f攻撃を回避しました");

                player.getWorld().spawnParticle(org.bukkit.Particle.CLOUD, player.getLocation().add(0, 1, 0), 5, 0.2, 0.5, 0.2, 0.05);
                return;
            }
        }

        // ==========================================
        // ★追加: 刀チャージ中に攻撃を受けたら「抜刀カウンター」発動！
        // ==========================================
        if (plugin.getSkillListener().isKatanaCharging(player)) {
            event.setCancelled(true);
            // ★ 同期実行（チャージタイマーのキャンセルと競合しないよう即発動）
            plugin.getSkillListener().triggerBattouCounter(player);
            return;
        }

        double finalDamage;

        if (isSaoMob) {
            // SAOモブの場合は、JSONの攻撃力 ± 5 のばらつきを与える
            // random.nextInt(11) で 0~10、そこから 5 引いて -5 ~ +5
            int variation = random.nextInt(11) - 5;
            finalDamage = mobBaseDamage + variation;
        } else {
            // 環境ダメージなど、SAOモブ以外はバニラのダメージをそのまま採用
            finalDamage = event.getDamage();
        }

        // ==========================================
        // ★追加: タンク(全身装備メンバー)からの防御力付与システム
        // ==========================================
        double bonusDefense = 0.0;
        if (plugin.getPartyManager().isInParty(player)) {
            for (Player member : plugin.getPartyManager().getOnlinePartyMembers(player)) {
                if (member.equals(player)) continue; // 自分自身からの付与はスキップ

                // メンバーが全身防具(4スロットすべて)を装備しているかチェック
                org.bukkit.inventory.ItemStack[] armors = member.getInventory().getArmorContents();
                boolean isFullArmor = true;
                double memberTotalDef = 0.0;

                for (org.bukkit.inventory.ItemStack armor : armors) {
                    // 1つでも空っぽだったり、SAO防具じゃなかったら全身装備ではないと判定
                    if (armor == null || armor.getType() == org.bukkit.Material.AIR || !armor.hasItemMeta() || !armor.getItemMeta().hasLore()) {
                        isFullArmor = false;
                        break;
                    }
                    String idLine = org.bukkit.ChatColor.stripColor(armor.getItemMeta().getLore().get(0));
                    sac.plugin.info.SACplugin.item.SAOArmor armorData = plugin.getSaoItemManager().getArmor(idLine);
                    if (armorData == null) {
                        isFullArmor = false;
                        break;
                    }
                    // JSONで設定された防具の防御力を合算
                    memberTotalDef += armorData.defense;
                }

                if (isFullArmor) {
                    // 全身装備しているタンクの「防御力の半分」を付与
                    // (※タンクが2人以上いる場合は、より硬い方のバフを適用する)
                    bonusDefense = Math.max(bonusDefense, memberTotalDef / 2.0);
                }
            }
        }

        // ★【変更】自身の防御力にタンクボーナスを加算してダメージカット！
        double totalDefense = stats.defense + bonusDefense;
        finalDamage = Math.max(1.0, finalDamage - totalDefense);

        stats.currentHP -= finalDamage;

        // ==========================================
        // ★修正: 防具の耐久値減少処理 (2回の被弾で1減るようにする！)
        // ==========================================
        boolean armorUpdated = false;
        String[] slots = {"head", "chest", "arm", "legs", "boots"};

        // まず、今回の被弾でカウントを1増やす
        int hits = armorHitCounter.getOrDefault(player.getUniqueId(), 0) + 1;
        int durabilityToReduce = hits / 2; // 2回で1減る (例: 3ヒットなら 3/2=1)
        armorHitCounter.put(player.getUniqueId(), hits % 2); // 割り切れなかった余りを保存

        // ★減らす耐久値が1以上(つまり2回殴られた)時だけ、防具を削る！
        if (durabilityToReduce > 0) {
            for (String slot : slots) {
                if (stats.equipment.containsKey(slot)) {
                    org.bukkit.inventory.ItemStack armor = stats.equipment.get(slot);
                    if (armor == null || !armor.hasItemMeta() || !armor.getItemMeta().hasLore()) continue;

                    org.bukkit.inventory.meta.ItemMeta meta = armor.getItemMeta();
                    java.util.List<String> lore = meta.getLore();
                    int currentDura = -1;
                    int maxDura = -1;
                    int duraIndex = -1;

                    // Loreの中から「Durability: 」と書かれた行を探す
                    for (int i = 0; i < lore.size(); i++) {
                        String plain = org.bukkit.ChatColor.stripColor(lore.get(i));
                        if (plain.startsWith("Durability: ")) {
                            duraIndex = i;
                            String[] parts = plain.replace("Durability: ", "").split(" / ");
                            if (parts.length == 2) {
                                try {
                                    currentDura = Integer.parseInt(parts[0].trim());
                                    maxDura = Integer.parseInt(parts[1].trim());
                                } catch (Exception ignored) {
                                }
                            }
                            break;
                        }
                    }

                    // 耐久値を計算して減らす
                    if (duraIndex != -1 && currentDura > 0) {
                        int prevDura = currentDura;
                        currentDura -= durabilityToReduce;

                        if (currentDura <= 0) {
                            // 壊れた場合の処理
                            player.sendMessage("§b[System] §r§c【警告】 装備していた " + meta.getDisplayName() + " §cが壊れました！");
                            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
                            stats.equipment.remove(slot);
                        } else {
                            // 耐久値がまだ残っている場合はLoreを更新
                            lore.set(duraIndex, "§7Durability: §a" + currentDura + " / " + maxDura);
                            meta.setLore(lore);
                            armor.setItemMeta(meta);

                            // ★追加: 武器と同じく、耐久値が10以下・5以下になった瞬間にだけ警告を表示
                            String armorName = meta.hasDisplayName() ? meta.getDisplayName() : "防具";
                            if (currentDura <= 5 && prevDura > 5) {
                                player.sendMessage("§b[System] §r§c【耐久値警告】 " + armorName + " §cの耐久値が残り5以下です！もうすぐ壊れます！");
                                player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_ANVIL_DESTROY, 1.0f, 1.5f);
                            } else if (currentDura <= 10 && prevDura > 10) {
                                player.sendMessage("§b[System] §r§e【耐久値注意】 " + armorName + " §eの耐久値が残り10以下になりました。");
                            }
                        }
                        armorUpdated = true;
                    }
                }
            }
        }

        // 装備が壊れたり耐久値が減った場合は、データを保存してステータス再計算
        if (armorUpdated) {
            plugin.getPlayerManager().savePlayerData(player, stats);
            // ★修正: syncHealth内のAttributeModifier例外がここで投げられると、
            // 以降のevent.setDamage(0)/setCancelled(true)/setHealth()が実行されず
            // バニラのダメージがそのまま適用されてしまう(ワンパン現象の原因)。
            // try-catchで保護し、必ずダメージ処理を完走させる。
            try {
                plugin.getPlayerManager().syncHealth(player, stats);
            } catch (Exception e) {
                plugin.getLogger().severe("[SAO] " + player.getName() + " のsyncHealthで例外発生！ダメージ処理は継続します。");
                e.printStackTrace();
            }
        }

        // 死亡判定
        if (stats.currentHP <= 0) {
            stats.currentHP = 0;
            player.setHealth(0); // 実際に死亡させる
            return;
        }

        // ハートの見た目（バニラHP）を更新 (割合計算)
        double visualHealth = (stats.currentHP / stats.maxHP) * 20.0;
        visualHealth = Math.min(20.0, Math.max(1.0, visualHealth));

        // setCancelled(true)も併用し、バニラのダメージ適用処理自体を完全にスキップする
        event.setCancelled(true);
        event.setDamage(0);

        player.setHealth(visualHealth);

        // ★追加: setCancelled(true)によりバニラの被ダメージ音(バチッという音)も消えてしまうため、
        // 明示的に再生して元の挙動を再現する
        player.getWorld().playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_HURT, 1.0f, 1.0f);

        // スコアボードを最新状態に更新
        plugin.getSaoScoreboard().updateScoreboard(player, stats);
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player)) return;
        Player player = (Player) event.getDamager();

        // ==========================================
        // ★追加: スタン・麻痺中は武器による攻撃を一切できないようにする
        // ==========================================
        if (plugin.getStatusEffectManager().isActionLocked(player)) {
            event.setCancelled(true);
            event.setDamage(0);
            return;
        }

        // ==========================================
        // ★追加: 疾走スキル発動中(selectedSkillId="dash")は、ソードスキルだけでなく
        // 通常の武器攻撃も一切できないようにする。
        // ==========================================
        PlayerData dashCheckStats = plugin.getPlayerManager().getStats(player);
        if (dashCheckStats != null && "dash".equals(dashCheckStats.selectedSkillId)) {
            event.setCancelled(true);
            event.setDamage(0);
            return;
        }

        // 1. スキルダメージの保護
        if (event.getDamage() <= 0.05) return;

        // --- ★ ここで先に武器を取得する（ノックバック停止を先に行うため） ---
        ItemStack item = player.getInventory().getItemInMainHand();
        ItemMeta meta;
        SAOWeapon weapon;

        if (item.getType() == Material.AIR) {
            // ==========================================
            // ★修正: 「セット」の判定を、既存の武器種ソードスキルと同じ仕組みに統一する。
            // equippedSkills(6枠のどれか)ではなく、現在選択中のソードスキル(selectedSkillId)の
            // カテゴリで判定する。体術の技を選択している間だけ素手攻撃として処理される。
            // ==========================================
            PlayerData preStats = plugin.getPlayerManager().getStats(player);
            String unarmedCategory = null;
            if (preStats != null && preStats.selectedSkillId != null && !preStats.selectedSkillId.isEmpty()) {
                sac.plugin.info.SACplugin.skill.SwordSkillData selectedSkill = plugin.getSwordSkillManager().getSkill(preStats.selectedSkillId);
                if (selectedSkill != null && plugin.getWeaponTypeManager().isUnarmed(selectedSkill.category)) {
                    unarmedCategory = selectedSkill.category;
                }
            }
            if (unarmedCategory == null) return;

            weapon = plugin.getWeaponTypeManager().buildUnarmedWeapon(player, unarmedCategory);

            // ★ 素手用の「ダミーアイテム」。耐久値行を持たないため、updateDurability内の
            // 物理耐久減少処理は自然にスキップされ、熟練度加算だけが行われる。
            ItemStack fakeStack = new ItemStack(Material.BARRIER);
            ItemMeta fakeMeta = fakeStack.getItemMeta();
            fakeMeta.setLore(java.util.Collections.singletonList("§8" + weapon.id));
            fakeStack.setItemMeta(fakeMeta);
            item = fakeStack;
            meta = fakeMeta;
        } else {
            if (!item.hasItemMeta() || !item.getItemMeta().hasLore()) {
                return;
            }
            meta = item.getItemMeta();
            String weaponId = org.bukkit.ChatColor.stripColor(meta.getLore().get(0));
            weapon = plugin.getSaoItemManager().getWeapon(weaponId);
        }

        if (weapon != null) {
            // ★追加: 旧仕様(単一の強化値)で強化されていた武器を新仕様に一度だけ変換する。
            // 強化・修理メニューを開かなくても、戦闘で使った時点で自動的に移行される。
            sac.plugin.info.SACplugin.Smithing.ForgeManager.migrateLegacyWeaponIfNeeded(item, weapon);

            boolean isSweep = event.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK;

            // 2. ★【ノックバック無効武器の判定をクールタイムの前に移動】
            // これにより、連打中（溜まっていない時）でもノックバックを止められます
            // ★変更: 武器種のハードコード判定をやめ、weapon_types.jsonの"knockback"フィールドで管理する
            if (!plugin.getWeaponTypeManager().isKnockbackEnabled(weapon.category)) {
                if (isSweep) {
                    event.setCancelled(true);
                    return;
                }
                if (event.getEntity() instanceof org.bukkit.entity.LivingEntity) {
                    org.bukkit.entity.LivingEntity victim = (org.bukkit.entity.LivingEntity) event.getEntity();
                    // ==========================================
                    // ★修正: ダメージ計算の瞬間に「ノックバック耐性」を100%にして物理的に動かなくする！
                    // ==========================================
                    org.bukkit.attribute.AttributeInstance kbResist = victim.getAttribute(org.bukkit.attribute.Attribute.GENERIC_KNOCKBACK_RESISTANCE);

                    if (kbResist != null) {
                        // 連続攻撃で元の耐性値がおかしくなるのを防ぐためのタグチェック
                        if (!victim.getScoreboardTags().contains("sao_kb_immune")) {
                            victim.addScoreboardTag("sao_kb_immune");
                            double originalKb = kbResist.getBaseValue();
                            kbResist.setBaseValue(1.0); // 1.0 = 100%ノックバック無効化

                            new org.bukkit.scheduler.BukkitRunnable() {
                                @Override
                                public void run() {
                                    if (victim.isValid()) {
                                        kbResist.setBaseValue(originalKb); // 1Tick後に元の耐性に戻す
                                        victim.removeScoreboardTag("sao_kb_immune");
                                        victim.setVelocity(new org.bukkit.util.Vector(0, 0, 0)); // 念のための停止
                                    }
                                }
                            }.runTaskLater(plugin, 1L);
                        }
                    } else {
                        // 万が一Attributeが取得できなかった時の保険
                        new org.bukkit.scheduler.BukkitRunnable() {
                            @Override
                            public void run() {
                                if (victim.isValid()) victim.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
                            }
                        }.runTaskLater(plugin, 1L);
                    }
                    victim.setNoDamageTicks(0);
                }
            }

            // 3. 【既存のクールタイム判定】 (ダメージ計算を行うかどうかの判定)
            float cooldown = player.getAttackCooldown();
            if (cooldown < 1.0f) {
                event.setDamage(0);
                return;
            }

            // 4. 【既存の全仕様を維持】
            // ==========================================
            // ★修正: バニラ無敵時間を最優先でリセット（複数人攻撃・連打対応）
            // ダメージ計算より前に呼ぶことで、直前の攻撃による無敵を確実に解除する
            // ==========================================
            if (event.getEntity() instanceof org.bukkit.entity.LivingEntity) {
                ((org.bukkit.entity.LivingEntity) event.getEntity()).setNoDamageTicks(0);
            }

            if (!(event.getEntity() instanceof org.bukkit.entity.LivingEntity))
                return; // 変更: Zombie限定ではなくLivingEntity全般に
            // ==========================================
            // ★追加: アーマースタンド(スポナーや家具)を殴った時は計算をストップする！
            // ==========================================
            if (event.getEntity() instanceof org.bukkit.entity.ArmorStand) return;
            // ==========================================
            // ★追加: NPC(村人)を殴った時も計算をストップする！
            // ==========================================
            if (event.getEntity() instanceof org.bukkit.entity.Villager) {
                org.bukkit.entity.Villager villager = (org.bukkit.entity.Villager) event.getEntity();
                // 名前がついている、または特定のタグを持っていればNPCとして弾く
                if (villager.getCustomName() != null) return;
                for (String tag : villager.getScoreboardTags()) {
                    if (tag.startsWith("sao_shop_") || tag.startsWith("sao_quest_")) return;
                }
            }
            LivingEntity targetMob = (LivingEntity) event.getEntity(); // 対象を取得

            PlayerData stats = plugin.getPlayerManager().getStats(player);
            if (stats == null) return;

            // 要求レベル判定(アクセサリーのREQボーナス分だけ緩和される)
            if (stats.level + stats.accReqBonus < weapon.requires) {
                event.setDamage(0);
                player.sendMessage("§b[System] §r§c要求レベル：§e" + weapon.requires);
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 0.5f);
                return;
            }

            // ==========================================
            // ★新規追加: 装備中のスキルと武器種が一致するか判定！
            // ==========================================
            if (stats.selectedSkillId == null || stats.selectedSkillId.isEmpty()) {
                event.setDamage(0);
                player.sendMessage("§b[System] §r§cソードスキルがセットされていないため、武器を扱えません！");
                player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_ITEM_BREAK, 1.0f, 0.5f);
                return;
            } else {
                sac.plugin.info.SACplugin.skill.SwordSkillData skillData = plugin.getSwordSkillManager().getSkill(stats.selectedSkillId);
                if (skillData != null && skillData.category != null && !skillData.category.equals(weapon.category)) {
                    event.setDamage(0);
                    player.sendMessage("§b[System] §r§cセット中のスキル(§e" + skillData.category + "§c)と武器種が違うため攻撃できません！");
                    player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_ITEM_BREAK, 1.0f, 0.5f);
                    return;
                }
            }

            if (isSweep) return;

            // ==========================================
            // ★追加: 細剣のコンボカウント処理
            // ==========================================
            if ("細剣".equals(weapon.category)) {
                int count = rapierComboCount.getOrDefault(player.getUniqueId(), 0);
                if (count < 7) {
                    count++;
                    rapierComboCount.put(player.getUniqueId(), count);

                    if (count == 7) {
                        player.sendMessage("§a[System] 細剣コンボ最大　次のスキルが強化されます");
                        player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 2.0f);
                    }
                }
                // ★ コンボカウントをアクションバーに表示（ダメージ表記なし）
                int displayCount = rapierComboCount.getOrDefault(player.getUniqueId(), 0);
                player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                        new net.md_5.bungee.api.chat.TextComponent("§a[Combo: " + displayCount + "/7]"));
            } else {
                // 細剣以外の武器で攻撃したらカウントを0にリセット
                rapierComboCount.put(player.getUniqueId(), 0);
            }

            // ダメージ計算と表示
            // ダメージ計算
            double forgeBonus = getWeaponBonus(item);    // 品質ボーナス
            double upgradeBonus = getUpgradeBonus(item); // 強化ボーナス

            // 基礎値に両方のボーナスを足す！
            double minAtk = weapon.minAttack + forgeBonus + upgradeBonus;
            double maxAtk = weapon.maxAttack + forgeBonus + upgradeBonus;

            // ==========================================
            // ★追加: 命中率判定(Accuracy)。基本20%でミスし、Accuracyボーナス1につき
            // ミス率が1%下がる(下限0%)。ミスした場合はダメージ0・MISS表示になる。
            // ==========================================
            int accuracyBonus = sac.plugin.info.SACplugin.Smithing.ForgeManager.getAccuracyBonus(item) + stats.accAccuracyBonus;
            int missChance = Math.min(100, Math.max(0, 20 - accuracyBonus)
                    + sac.plugin.info.SACplugin.life.VitalityManager.accuracyPenalty(stats));
            boolean isMiss = random.nextInt(100) < missChance;

            double rawDamage = minAtk + (maxAtk - minAtk) * random.nextDouble();
            int finalDamage = isMiss ? 0 : (int) Math.round(rawDamage);

            // ==========================================
            // ★追加: 両手剣がSTRで重量を上回り、最高速度(maxSpeedBpm)に達している場合、
            // 通常攻撃ダメージを1.5倍にする。SAOSTRListenerの速度計算式と同じロジックで判定する。
            // ==========================================
            if (!isMiss && "両手剣".equals(weapon.category)) {
                sac.plugin.info.SACplugin.item.WeaponTypeManager wtm = plugin.getWeaponTypeManager();
                boolean strAffectsSpeed = wtm == null || wtm.isStrAffectingSpeed(weapon.category);
                if (strAffectsSpeed) {
                    boolean weightAffectsSpeed = wtm == null || wtm.isWeightAffectingSpeed(weapon.category);
                    double weightTerm = weightAffectsSpeed ? (weapon.weight / 100.0) : 0.0;
                    int effectiveStr = stats.str + stats.armorStrBonus;
                    double speedRatio = 0.4 - weightTerm + (effectiveStr * 0.025);
                    double maxSpeedRatio = wtm != null ? wtm.getMaxSpeedRatio(weapon.category) : 1.0;

                    if (speedRatio >= maxSpeedRatio) {
                        finalDamage = (int) Math.round(finalDamage * 1.3);
                    }
                }
            }

            if (isMiss) {
                event.setDamage(0);
                final org.bukkit.Location missLoc = targetMob.getLocation().clone();
                final double missHeight = targetMob.getHeight();
                org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () ->
                        showMissPopupAt(plugin, missLoc, missHeight), 1L);

                // 素振り自体は成立しているため、攻撃音と耐久値消費は通常通り発生させる
                if (!plugin.getWeaponTypeManager().isUnarmed(weapon.category)) {
                    player.getWorld().playSound(player.getLocation(), "sao.attack.normal", 5.0f, 1.0f);
                }
                updateDurability(player, item, meta, weapon, 1);
                return;
            }

            // ★変更: ここにあった event.setDamage((double) finalDamage); は消します！

            if (finalDamage > 0) {
                // ★ ダメージポップアップは座標をキャッシュして表示（死亡時もモブが消える前に座標取得）
                final org.bukkit.Location mobLocForPopup = targetMob.getLocation().clone();
                final double popupDamage = finalDamage;
                org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () ->
                        showDamagePopupAt(plugin, mobLocForPopup, targetMob.getHeight(), popupDamage, false), 1L);

                // ★修正: 体術など「素手武器種」は剣の攻撃音を鳴らさない
                if (!plugin.getWeaponTypeManager().isUnarmed(weapon.category)) {
                    player.getWorld().playSound(player.getLocation(), "sao.attack.normal", 5.0f, 1.0f);
                }
            }
            updateDurability(player, item, meta, weapon, 1);
            if (finalDamage > 0) {
            }

            boolean isSaoMob = false;
            String saoMobId = null;
            for (String tag : targetMob.getScoreboardTags()) {
                if (tag.startsWith("sao_mob_")) {
                    isSaoMob = true;
                    saoMobId = tag.replace("sao_mob_", "");
                    break;
                }
            }

            // ==========================================
            // ★変更: バニラの処理を待たず、自分でHPを引いて即座にバーを更新する！
            // ==========================================
            if (isSaoMob) {
                double currentHealth = targetMob.getHealth();
                double newHealth = Math.max(0.0, currentHealth - finalDamage);

                targetMob.setHealth(newHealth);
                // ★追加: バニラのgetKiller()に頼らず、確実にここで記録する
                lastDamagerMap.put(targetMob.getUniqueId(), player.getUniqueId());

                // ==========================================
                // ★追加: レイド化対応。フロアボス/フィールドボスに与えたダメージを
                // プレイヤーごとに記録する(討伐時の経験値・Colの倍率計算に使う)。
                // ==========================================
                if (saoMobId != null) {
                    sac.plugin.info.SACplugin.mob.SAOMobData hitMobData = plugin.getSaoMobManager().getMobData(saoMobId);
                    if (hitMobData != null && ("FLOOR_BOSS".equals(hitMobData.category) || "FIELD_BOSS".equals(hitMobData.category))) {
                        plugin.getRaidManager().recordBossDamage(targetMob.getUniqueId(), player, finalDamage);
                    }
                }

                // ==========================================
                // ★追加: バニラの赤いハート回復エフェクトの代わりに、白いパーティクルを一瞬だけ表示する
                // ==========================================
                // ★修正: EXPLOSIONから白色のDUSTに変更。
                org.bukkit.Particle.DustOptions whiteDust = new org.bukkit.Particle.DustOptions(org.bukkit.Color.WHITE, 1.2f);
                targetMob.getWorld().spawnParticle(
                        org.bukkit.Particle.DUST,
                        targetMob.getLocation().add(0, targetMob.getHeight() / 2.0, 0),
                        14, 0.3, 0.3, 0.3, 0.15, whiteDust
                );

                // ★修正: 0.001だとバニラのダメージ処理が走り、その後NoDamageTicksが再付与されてしまう。
                // 0にすることでバニラのダメージ処理を完全にキャンセルし、無敵時間が付与されるのを防ぐ。
                // HPはすでに上で直接setHealth()しているので問題なし。
                event.setDamage(0);

                // ★ 念のため: バニラ処理後も無敵が残らないよう1tick後にもリセット（複数人同時攻撃の保険）
                final LivingEntity mobForReset = targetMob;
                org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (mobForReset.isValid() && !mobForReset.isDead()) {
                        mobForReset.setNoDamageTicks(0);
                    }
                }, 1L);

                plugin.getSaoMobManager().updateHpBar(targetMob);

                // ==========================================
                // ★追加: 短剣の高速5連撃 (AGI値×0.5% の確率で発動)
                // ==========================================
                if ("短剣".equals(weapon.category)) {
                    // ★変更: 防具のAGIボーナス(金属防具鍛造)も合算する
                    double triggerChance = (stats.agi + stats.armorAgiBonus) * 0.002; // AGI1につき0.5%
                    if (Math.random() < triggerChance) {
                        double minDmg = weapon.minAttack + forgeBonus + upgradeBonus;

                        player.sendMessage("§e[短剣] §b高速連撃発動！");
                        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 2.0f);

                        // ★ 1発目のエフェクト（通常攻撃ヒット分）
                        org.bukkit.Location firstHitLoc = targetMob.getLocation().add(0, 1, 0);
                        firstHitLoc.getWorld().spawnParticle(org.bukkit.Particle.SWEEP_ATTACK, firstHitLoc, 1, 0.1, 0.1, 0.1, 0);
                        firstHitLoc.getWorld().spawnParticle(org.bukkit.Particle.CRIT, firstHitLoc, 5, 0.2, 0.2, 0.2, 0.1);

                        // 1発目はすでに与えているので残り4発を2tickごとに遅延実行
                        for (int hit = 1; hit <= 4; hit++) {
                            final int hitNum = hit;
                            final LivingEntity finalTarget = targetMob;
                            final double hitDmg = minDmg;
                            org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
                                if (!finalTarget.isValid() || finalTarget.isDead()) return;

                                double hp = finalTarget.getHealth();
                                double afterHp = Math.max(0.0, hp - hitDmg);
                                finalTarget.setHealth(afterHp);
                                finalTarget.setNoDamageTicks(0);

                                plugin.getSaoMobManager().updateHpBar(finalTarget);

                                // ★ 斬撃エフェクト（SWEEP_ATTACKパーティクル）
                                org.bukkit.Location hitLoc = finalTarget.getLocation().add(0, 1, 0);
                                hitLoc.getWorld().spawnParticle(
                                        org.bukkit.Particle.SWEEP_ATTACK,
                                        hitLoc, 1, 0.1, 0.1, 0.1, 0);
                                hitLoc.getWorld().spawnParticle(
                                        org.bukkit.Particle.CRIT,
                                        hitLoc, 5, 0.2, 0.2, 0.2, 0.1);

                                // ★ シュッという斬撃音
                                player.playSound(hitLoc,
                                        org.bukkit.Sound.ENTITY_PLAYER_ATTACK_SWEEP,
                                        0.8f, 1.6f + (hitNum * 0.1f));

                                // ★ 各ヒットをポップアップ表示（短剣5連撃=白）
                                showDamagePopup(plugin, finalTarget, hitDmg, false);

                                // 最終ヒット時に連撃終了フラグを解除
                                if (hitNum == 4) {
                                    daggerComboActive.remove(player.getUniqueId());
                                }
                            }, hit * 2L);
                        }
                    }
                }

            } else {
                // SAOモブ以外(牛や豚など)はバニラ通りダメージを与える
                event.setDamage((double) finalDamage);
            }
        }
    }

    // ==========================================
    // ★追加: 熟練度(経験値)加算だけを行う。素手武器種(体術など)のように
    // 物理アイテムの耐久値を持たない攻撃でも呼び出せるよう、updateDurabilityから分離した。
    // ==========================================
    public static void updateProficiency(Player player, String category, int amount) {
        if (category == null) return;

        sac.plugin.info.SACplugin.SACplugin mainInstance = sac.plugin.info.SACplugin.SACplugin.getPlugin(sac.plugin.info.SACplugin.SACplugin.class);
        PlayerData stats = mainInstance.getPlayerManager().getStats(player);
        if (stats == null) return;

        int currentLevel = stats.proficiency.getOrDefault(category, 1);
        int limit = mainInstance.getLimitManager().getSkillLimit(category); // JSONから上限取得

        // 現在のレベルが上限未満のときだけ、経験値(使った耐久値)を加算する
        if (currentLevel < limit) {
            int used = stats.usedDurability.getOrDefault(category, 0);
            used += amount;

            // ★変更: 武器の熟練度必要経験値を 300 + 10×(レベル-1) に変更
            int requiredExp = 300 + (currentLevel - 1) * 10;

            if (used >= requiredExp) {
                stats.proficiency.put(category, currentLevel + 1);
                stats.usedDurability.put(category, used - requiredExp);

                player.sendMessage("§b[SKILL UP] §f" + category + "の熟練度が §e" + (currentLevel + 1) + " §fになりました！");
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
            } else {
                stats.usedDurability.put(category, used);
            }
        }

        // 保存とスコアボード更新は上限に関わらず実行
        mainInstance.getPlayerManager().savePlayerData(player, stats);
        if (mainInstance.getSaoScoreboard() != null) {
            mainInstance.getSaoScoreboard().updateScoreboard(player, stats);
        }
    }

    public static void updateDurability(Player player, ItemStack item, ItemMeta meta, SAOWeapon weapon, int amount) {
        if (meta == null || !meta.hasLore()) return;

        updateProficiency(player, weapon.category, amount);

        // ==========================================
        // 2. 耐久値減少処理 (2回ヒットするごとに1減らす！)
        // ==========================================
        int hits = weaponHitCounter.getOrDefault(player.getUniqueId(), 0) + amount;
        int durabilityToReduce = hits / 2; // 2回で1減る (例: 3ヒットなら 3/2=1)
        weaponHitCounter.put(player.getUniqueId(), hits % 2); // 割り切れなかった余りを保存 (例: 3%2=1)

        // 減らす耐久値が1以上溜まった時だけ実行する
        if (durabilityToReduce > 0) {
            List<String> lore = meta.getLore();
            int durabilityLineIndex = -1;
            for (int i = 0; i < lore.size(); i++) {
                if (lore.get(i).contains("Durability:")) {
                    durabilityLineIndex = i;
                    break;
                }
            }

            if (durabilityLineIndex != -1) {
                try {
                    String line = org.bukkit.ChatColor.stripColor(lore.get(durabilityLineIndex));
                    String[] parts = line.replace("Durability:", "").trim().split("/");

                    if (parts.length == 2) {
                        int prevDur = Integer.parseInt(parts[0].trim());
                        int currentDur = prevDur - durabilityToReduce;
                        int maxDur = Integer.parseInt(parts[1].trim());

                        if (currentDur <= 0) {
                            player.getInventory().setItemInMainHand(null);
                            player.playSound(player.getLocation(), Sound.BLOCK_GLASS_BREAK, 1.0f, 0.8f);
                            player.sendMessage("§b[System] §r§c武器が破壊されました");
                        } else {
                            lore.set(durabilityLineIndex, "§7Durability: §a" + currentDur + " / " + maxDur);
                            meta.setLore(lore);
                            item.setItemMeta(meta);

                            // ★新要素: 耐久値が10以下・5以下になった瞬間にだけ警告を表示
                            String weaponName = meta.hasDisplayName() ? meta.getDisplayName() : "武器";
                            if (prevDur > 5 && currentDur <= 5) {
                                player.sendMessage("§b[System] §r§c【耐久値警告】 " + weaponName + " §cの耐久値が残り5以下です！もうすぐ壊れます！");
                                player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.7f, 1.5f);
                            } else if (prevDur > 10 && currentDur <= 10) {
                                player.sendMessage("§b[System] §r§e【耐久値注意】 " + weaponName + " §eの耐久値が残り10以下になりました。");
                                player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.5f, 1.2f);
                            }
                        }
                    }
                } catch (Exception e) {
                    org.bukkit.Bukkit.getLogger().warning("耐久値の更新に失敗しました");
                }
            }
        }
    }

    //NPC（村人）へのダメージを完全に無効化する
    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
    public void onVillagerDamage(org.bukkit.event.entity.EntityDamageEvent event) {
        // ダメージを受けたのが村人かどうか
        if (event.getEntity() instanceof org.bukkit.entity.Villager) {
            org.bukkit.entity.Villager villager = (org.bukkit.entity.Villager) event.getEntity();

            // ★【追加】ネザーライトの剣での攻撃なら無敵を貫通（ダメージ許可）する
            if (event instanceof EntityDamageByEntityEvent) {
                EntityDamageByEntityEvent byEntityEvent = (EntityDamageByEntityEvent) event;
                if (byEntityEvent.getDamager() instanceof Player) {
                    Player player = (Player) byEntityEvent.getDamager();
                    if (player.getInventory().getItemInMainHand().getType() == Material.NETHERITE_SWORD) {
                        return; // ここで判定を終わらせる（＝キャンセルされないのでダメージが入る）
                    }
                }
            }

            // 1. ショップやクエストのタグを持っているか確認
            for (String tag : villager.getScoreboardTags()) {
                if (tag.startsWith("sao_shop_") || tag.startsWith("sao_quest_")) {
                    event.setCancelled(true); // ダメージをキャンセル（無敵）
                    return;
                }
            }

            // 2. 念のため：名前(CustomName)が設定されている村人はすべて無敵にする
            if (villager.getCustomName() != null) {
                event.setCancelled(true);
            }
        }
    }

    // ==========================================
    // ★追加: スポナー(アーマースタンド)の保護（OP以外は破壊不可）
    // ==========================================
    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
    public void onSpawnerDamage(org.bukkit.event.entity.EntityDamageEvent event) {
        if (event.getEntity() instanceof org.bukkit.entity.ArmorStand) {
            org.bukkit.entity.ArmorStand stand = (org.bukkit.entity.ArmorStand) event.getEntity();
            for (String tag : stand.getScoreboardTags()) {
                if (tag.startsWith("sao_spawner_")) {
                    if (event instanceof org.bukkit.event.entity.EntityDamageByEntityEvent) {
                        org.bukkit.event.entity.EntityDamageByEntityEvent byEntity = (org.bukkit.event.entity.EntityDamageByEntityEvent) event;
                        if (byEntity.getDamager() instanceof Player) {
                            Player player = (Player) byEntity.getDamager();
                            if (player.isOp()) {
                                return; // OPなら壊せる
                            }
                        }
                    }
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }

    // ==========================================
    // ★追加: パリィ用の「耐久値だけを消費し、熟練度は上げない」メソッド
    // ==========================================
    // ==========================================
    // ★追加: APEXスタイルのダメージポップアップ
    // 通常攻撃 → 白 (§f)
    // ソードスキル・カウンター → 濃い水色 (§3)
    // ==========================================

    /**
     * 座標を直接受け取るバージョン（モブ死亡後も表示できる）
     */
    public static void showDamagePopupAt(org.bukkit.plugin.Plugin plugin, org.bukkit.Location loc, double entityHeight, double damage, boolean isSkill) {
        showDamagePopupAt(plugin, loc, entityHeight, String.valueOf((int) damage), isSkill ? "§3" : "§f", isSkill);
    }

    // ★追加: 命中率判定でミスした時に「MISS」を表示するためのオーバーロード
    public static void showMissPopupAt(org.bukkit.plugin.Plugin plugin, org.bukkit.Location loc, double entityHeight) {
        showDamagePopupAt(plugin, loc, entityHeight, "MISS", "§7", false);
    }

    private static void showDamagePopupAt(org.bukkit.plugin.Plugin plugin, org.bukkit.Location loc, double entityHeight, String text, String color, boolean isSkill) {
        if (plugin == null || loc == null || loc.getWorld() == null) return;

        double offsetX = (Math.random() - 0.5) * 0.8;
        double offsetZ = (Math.random() - 0.5) * 0.8;
        org.bukkit.Location popupLoc = loc.clone().add(offsetX, entityHeight + 0.3, offsetZ);

        // ★修正: ArmorStand → TextDisplay に統一（軽量化・文字サイズ変更可能に）
        String displayText = color + text;

        org.bukkit.entity.TextDisplay display = (org.bukkit.entity.TextDisplay)
                loc.getWorld().spawnEntity(popupLoc, org.bukkit.entity.EntityType.TEXT_DISPLAY);
        display.setText(displayText);
        display.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
        display.setPersistent(false);
        display.setGravity(false);
        display.setDefaultBackground(false);
        display.setShadowed(true);

        // ★文字サイズ: scaleのVector3fで調整（大きくしたい場合はここの値を増やす）
        float scale = isSkill ? 2.0f : 1.5f; // スキルダメージは少し大きく
        display.setTransformation(new org.bukkit.util.Transformation(
                new org.joml.Vector3f(0, 0, 0),
                new org.joml.AxisAngle4f(0, 0, 0, 0),
                new org.joml.Vector3f(scale, scale, scale),
                new org.joml.AxisAngle4f(0, 0, 0, 0)
        ));

        final org.bukkit.Location startLoc = popupLoc.clone();
        new org.bukkit.scheduler.BukkitRunnable() {
            int tick = 0;

            @Override
            public void run() {
                if (!display.isValid()) {
                    cancel();
                    return;
                }
                tick++;
                display.teleport(startLoc.clone().add(0, tick * 0.03, 0));
                if (tick >= 15) {
                    display.remove();
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    public static void showDamagePopup(org.bukkit.plugin.Plugin plugin, org.bukkit.entity.LivingEntity target, double damage, boolean isSkill) {
        if (plugin == null || !target.isValid()) return;
        org.bukkit.World world = target.getWorld();

        double offsetX = (Math.random() - 0.5) * 0.8;
        double offsetZ = (Math.random() - 0.5) * 0.8;
        org.bukkit.Location popupLoc = target.getLocation()
                .add(offsetX, target.getHeight() + 0.3, offsetZ);

        String color = isSkill ? "§3" : "§f";
        String text = color + (int) damage;

        org.bukkit.entity.TextDisplay display = (org.bukkit.entity.TextDisplay)
                world.spawnEntity(popupLoc, org.bukkit.entity.EntityType.TEXT_DISPLAY);
        display.setText(text);
        display.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
        display.setPersistent(false);
        display.setGravity(false);
        display.setDefaultBackground(false);
        display.setShadowed(true);

        org.bukkit.util.Transformation tf = display.getTransformation();
        float scale = isSkill ? 2.0f : 1.5f;
        display.setTransformation(new org.bukkit.util.Transformation(
                tf.getTranslation(),
                tf.getLeftRotation(),
                new org.joml.Vector3f(scale, scale, scale),
                tf.getRightRotation()
        ));

        final org.bukkit.Location startLoc = popupLoc.clone();
        new org.bukkit.scheduler.BukkitRunnable() {
            int tick = 0;

            @Override
            public void run() {
                if (!display.isValid()) {
                    cancel();
                    return;
                }
                tick++;
                display.teleport(startLoc.clone().add(0, tick * 0.03, 0));
                if (tick >= 15) {
                    display.remove();
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    // ==========================================
    // ★追加: パリィ用の「耐久値だけを消費し、熟練度は上げない」メソッド
    // ==========================================
    public static void reduceDurabilityOnly(Player player, ItemStack item, int amount) {
        if (item == null || !item.hasItemMeta() || !item.getItemMeta().hasLore()) return;
        ItemMeta meta = item.getItemMeta();
        java.util.List<String> lore = meta.getLore();

        int durabilityLineIndex = -1;
        for (int i = 0; i < lore.size(); i++) {
            if (org.bukkit.ChatColor.stripColor(lore.get(i)).startsWith("Durability:")) {
                durabilityLineIndex = i;
                break;
            }
        }

        if (durabilityLineIndex != -1) {
            try {
                String line = org.bukkit.ChatColor.stripColor(lore.get(durabilityLineIndex));
                String[] parts = line.replace("Durability:", "").trim().split("/");

                if (parts.length == 2) {
                    int prevDur = Integer.parseInt(parts[0].trim());
                    int currentDur = prevDur - amount;
                    int maxDur = Integer.parseInt(parts[1].trim());

                    if (currentDur <= 0) {
                        player.getInventory().setItemInMainHand(null);
                        player.playSound(player.getLocation(), Sound.BLOCK_GLASS_BREAK, 1.0f, 0.8f);
                        player.sendMessage("§b[System] §r§c武器が破壊されました");
                    } else {
                        lore.set(durabilityLineIndex, "§7Durability: §a" + currentDur + " / " + maxDur);
                        meta.setLore(lore);
                        item.setItemMeta(meta);

                        // ★新要素: 耐久値が10以下・5以下になった瞬間にだけ警告を表示
                        String weaponName = meta.hasDisplayName() ? meta.getDisplayName() : "武器";
                        if (prevDur > 5 && currentDur <= 5) {
                            player.sendMessage("§b[System] §r§c【耐久値警告】 " + weaponName + " §cの耐久値が残り5以下です！もうすぐ壊れます！");
                            player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.7f, 1.5f);
                        } else if (prevDur > 10 && currentDur <= 10) {
                            player.sendMessage("§b[System] §r§e【耐久値注意】 " + weaponName + " §eの耐久値が残り10以下になりました。");
                            player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.5f, 1.2f);
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }
}