package sac.plugin.info.SACplugin.mob;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import sac.plugin.info.SACplugin.Player.PlayerData;
import sac.plugin.info.SACplugin.SACplugin;
import sac.plugin.info.SACplugin.item.SAOGeneralItemData;
import sac.plugin.info.SACplugin.item.SAOWeapon;

import java.util.Map;
import java.util.Random;

public class SAOMobListener implements Listener {
    private final SACplugin plugin;

    public SAOMobListener(SACplugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onMobDeath(EntityDeathEvent event) {

        LivingEntity entity = event.getEntity();
        Player killer = entity.getKiller();

        // ==========================================
        // ★追加: バニラのgetKiller()がnullを返す場合のフォールバック。
        // このプラグインはevent.setDamage()を使わず手動でHPを減らしているため、
        // 連続で敵を倒すとバニラ側の「最後に殴ったプレイヤー」の記録が正しく
        // 更新されないことがある。その場合はSAODamageListener側で確実に記録している
        // lastDamagerMapから直接キラーを補完する。
        // ==========================================
        if (killer == null) {
            java.util.UUID lastDamagerUuid = sac.plugin.info.SACplugin.Event.SAODamageListener.lastDamagerMap.get(entity.getUniqueId());
            if (lastDamagerUuid != null) {
                org.bukkit.entity.Player fallbackKiller = org.bukkit.Bukkit.getPlayer(lastDamagerUuid);
                if (fallbackKiller != null && fallbackKiller.isOnline()) {
                    killer = fallbackKiller;
                }
            }
        }
        // ★追加: メモリリーク防止(モブが消えるので記録も掃除する)
        sac.plugin.info.SACplugin.Event.SAODamageListener.lastDamagerMap.remove(entity.getUniqueId());

// Locationを最初にキャッシュ
        Location loc = entity.getLocation().add(0, 1, 0);
        Location deathLoc = entity.getLocation(); // パーティクル用の元座標

        // バニラの死亡エフェクト・ドロップを無効化
        event.setDroppedExp(0);
        event.getDrops().clear();

        // ==========================================
        // ★追加: チュートリアルで出現させたモブは、倒してもEXP/Col/アイテムを一切与えない。
        // (バニラドロップは上ですでに無効化済みなので、ここではSAO側の報酬処理だけスキップする)
        // ==========================================
        if (entity.getScoreboardTags().contains("sao_tutorial_mob")) {
            entity.getWorld().spawnParticle(
                    org.bukkit.Particle.LARGE_SMOKE,
                    deathLoc.clone().add(0, 1, 0),
                    40, 0.2, 0.2, 0.2, 0.3
            );
            org.bukkit.Bukkit.getScheduler().runTask(plugin, entity::remove);
            return;
        }

        // SAOエフェクトを出す
        entity.getWorld().spawnParticle(
                org.bukkit.Particle.LARGE_SMOKE,
                deathLoc.clone().add(0, 1, 0),
                40, 0.2, 0.2, 0.2, 0.3
        );

        // entity.remove() を1tick後に遅延実行（イベント処理を壊さない）
        org.bukkit.Bukkit.getScheduler().runTask(plugin, entity::remove);

        // ★追加: 通常攻撃クールタイム管理マップからもエントリを削除（メモリリーク防止）
        sac.plugin.info.SACplugin.Event.SAODamageListener.mobAttackCooldowns.remove(entity.getUniqueId());

        // ★追加: フロアボスであればボスバーの表示も消す(該当しなければ何もしない)
        if (plugin.getBossBarManager() != null) {
            plugin.getBossBarManager().removeBossBar(entity.getUniqueId());
        }

        for (String tag : entity.getScoreboardTags()) {
            if (tag.startsWith("sao_mob_")) {
                String mobId = tag.replace("sao_mob_", "");
                SAOMobData mobData = plugin.getSaoMobManager().getMobData(mobId);

                if (mobData != null) {

                    entity.getWorld().playSound(loc, "sao.death.event", 0.1f, 1.0f);

                    boolean isBossCategory = "FLOOR_BOSS".equals(mobData.category) || "FIELD_BOSS".equals(mobData.category);

                    // ==========================================
                    // ★変更: フロアボス/フィールドボスはレイド化に伴い、
                    // 部屋にいる全員にダメージ割合に応じた報酬とドロップを個別に配る。
                    // 通常モブは従来通りkiller中心のパーティー共有処理。
                    // ==========================================
                    if (isBossCategory) {
                        handleBossRewardsAndDrops(entity, mobId, mobData, killer);
                    } else if (killer != null) {
                        PlayerData stats = plugin.getPlayerManager().getStats(killer);

                        if (stats != null) {
                            // ★追加(実績): 通常モブの討伐数を記録する
                            if (plugin.getAchievementManager() != null) plugin.getAchievementManager().onMobKill(killer, mobId);

                            // ★変更: exp/colをmobs.json側の範囲([exp,expMax]/[col,colMax])で個別に抽選する
                            int finalExp = mobData.rollExp();
                            long finalCol = mobData.rollCol();

                            // ==========================================
                            // ★変更: 格下補正(レベル差5ごとに10%減少)をexp・col両方に適用する。
                            // ただし最終的な受け取り額は最低5を下回らない(0にはならない)。
                            // 代わりに、格下狩りの時はドロップ率に+20%のボーナスが付く(下のtally処理側)。
                            // ==========================================
                            boolean isUnderleveled = stats.level > mobData.level;
                            if (isUnderleveled) {
                                int diff = stats.level - mobData.level;
                                double reductionMultiplier = (diff / 5) * 0.1;
                                double multiplier = Math.max(0.0, 1.0 - reductionMultiplier);

                                // ==========================================
                                // ★修正: 小数の誤差による「切り捨て」を防ぐため、四捨五入(Math.round)に変更！
                                // 最終値は最低5を保証する(格下狩りでも0にはならない)。
                                // ==========================================
                                finalExp = Math.max(5, (int) Math.round(finalExp * multiplier));
                                finalCol = Math.max(5L, Math.round(finalCol * multiplier));

                                if (reductionMultiplier > 0) {
                                    // メッセージの%表記も、誤差で 59% 等にならないよう四捨五入する
                                    killer.sendMessage("§7[System] 格下補正によりEXP・Colが " + (int)Math.round(Math.min(1.0, reductionMultiplier) * 100) + "% 減少しました。(代わりにドロップ率+20%)");
                                }
                            }

                            // 倒した本人のEXPとColの獲得
                            // ★変更: パーティーボーナス(1.2倍)もexpのみに適用し、colには一切かからないようにする。
                            boolean inParty = plugin.getPartyManager().isInParty(killer);
                            if (inParty) {
                                finalExp = (int) Math.round(finalExp * 1.2);
                            }

                            stats.exp += finalExp;
                            stats.col += finalCol;

                            // ==========================================
                            // ★変更: アイテムをプレイヤーのインベントリへ直接付与する処理を先に行い、
                            // 実際に入手できた個数をカウントしてからテキストディスプレイで一括表示する。
                            // (ドロップ処理は元々このブロックの外にあったが、表示用に個数が必要なため
                            //  ここで先に処理してitemsGivenを求める)
                            // ==========================================
                            int itemsGiven = 0;
                            if (!isBossCategory && mobData.drops != null) {
                                // ★変更: 同じIDが複数回登録されている場合、各行を独立抽選して
                                // 個数を積み上げてから、最後にまとめて1回で付与する。
                                java.util.Map<String, Integer> tally = new java.util.LinkedHashMap<>();
                                // ==========================================
                                // ★変更: 格下狩り(+0.2)と投票バフの上乗せ分は「加算」で合算する。
                                // 例: 格下狩り(1.2倍)+投票バフ(2.0倍) → 1.0 + 0.2 + 1.0 = 2.2倍
                                // ==========================================
                                double voteMultiplier = plugin.getVoteManager().getDropRateMultiplier(killer);
                                double bonusTotal = (isUnderleveled ? 0.2 : 0.0) + Math.max(0.0, voteMultiplier - 1.0);
                                double totalDropMultiplier = 1.0 + bonusTotal;
                                for (SAOMobData.DropEntry entry : mobData.drops) {
                                    if (entry == null || entry.id == null) continue;
                                    double effectiveChance = Math.min(1.0, entry.chance * totalDropMultiplier);
                                    if (Math.random() >= effectiveChance) continue;
                                    tally.merge(entry.id, 1, Integer::sum);
                                }

                                for (Map.Entry<String, Integer> tallyEntry : tally.entrySet()) {
                                    ItemStack dropItem = resolveDropItemForNormalMob(tallyEntry.getKey());
                                    if (dropItem == null) continue;
                                    int amount = tallyEntry.getValue();
                                    dropItem.setAmount(amount);

                                    // ★変更: インベントリに空きがある場合のみ付与する(空きが無ければ何もしない)
                                    java.util.HashMap<Integer, ItemStack> leftover = killer.getInventory().addItem(dropItem);
                                    int leftoverAmount = leftover.values().stream().mapToInt(ItemStack::getAmount).sum();
                                    itemsGiven += (amount - leftoverAmount);
                                }
                            }

                            // ==========================================
                            // ★変更: チャットでの「+EXP +Col」表示を廃止し、討伐地点に
                            // 2秒間だけ表示される小さめのテキストディスプレイに置き換える。
                            // ==========================================
                            spawnRewardDisplay(loc, finalExp, finalCol, itemsGiven);
                            processLevelUp(killer, stats);

                            // ==========================================
                            // ★修正: 共有は等倍＆レベル制限あり＆45ブロック以内！
                            // ==========================================
                            if (inParty) {
                                for (Player member : plugin.getPartyManager().getOnlinePartyMembers(killer)) {
                                    if (member.equals(killer)) continue; // 倒した本人はスキップ

                                    // ★追加: 倒したプレイヤーと同じワールド、かつ45ブロック以内にいるかチェック！
                                    if (member.getWorld().equals(killer.getWorld()) &&
                                            member.getLocation().distanceSquared(killer.getLocation()) <= 2025) { // 45^2 = 2025

                                        PlayerData memberStats = plugin.getPlayerManager().getStats(member);
                                        if (memberStats != null) {
                                            // 条件: 共有されるプレイヤーがモンスターのレベル以上であること
                                            if (memberStats.level >= mobData.level) {
                                                // ★パーティーボーナス済みのEXPをそのまま共有
                                                memberStats.exp += finalExp;
                                                member.sendMessage("§e[System] §a+" + finalExp + " EXP §7[共有+20%]");

                                                processLevelUp(member, memberStats); // 共有された側もレベルアップ判定
                                                plugin.getPlayerManager().savePlayerData(member, memberStats);
                                                plugin.getSaoScoreboard().updateScoreboard(member, memberStats);
                                            }
                                            // ★修正: レベル不足の時のエラーメッセージ(else)を削除しました！
                                        }
                                    }
                                }
                            }

                            if (stats.currentQuestId != null && !stats.currentQuestId.isEmpty()) {
                                sac.plugin.info.SACplugin.npc.SAOQuestData quest = plugin.getQuestManager().getQuest(stats.currentQuestId);
                                if (quest != null && "MOB".equals(quest.type) && quest.targetId.equals(mobId)) {
                                    if (!stats.questCompleted) {
                                        stats.questProgress++;
                                        String targetName = mobData.name != null ? mobData.name : mobId;
                                        killer.sendMessage("§b[System] " + targetName + " 討伐: " + stats.questProgress + "/" + quest.requiredAmount);

                                        if (stats.questProgress >= quest.requiredAmount) {
                                            stats.questCompleted = true;
                                            killer.sendMessage("§b[System] §r§aクエスト目標達成！NPCに報告してください。");
                                            killer.playSound(killer.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
                                        }
                                    }
                                }
                            }

                            plugin.getSaoScoreboard().updateScoreboard(killer, stats);
                            plugin.getPlayerManager().savePlayerData(killer, stats);
                        }
                    }

                    // ★ ボス討伐なら、20秒後の脱出処理を呼び出す
                    if (isBossCategory) {
                        // ★ 連鎖ボス(Nato→Baran)は途中なので街帰還・解放記録をスキップ
                        // BossAIRegistry の onDeath で次のボスをスポーンする
                        boolean isChainIntermediate =
                                mobId.equals("nato_the_colonel_taurus") ||
                                        mobId.equals("baran_the_general_taurus") ||
                                        mobId.equals("asterios_the_taurus_king");

                        if (!isChainIntermediate && plugin.getBossManager() != null) {
                            plugin.getBossManager().handleBossDefeat(mobId);

                            // ボス部屋内にいる全プレイヤーの討伐記録に登録
                            for (Player member : plugin.getBossManager().getPlayersInBossRoom(mobId)) {
                                plugin.getCrystalManager().recordBossDefeat(member, mobId);
                            }
                        }
                    }

                    // ==========================================
                    // ★追加: ゲームクリア判定 (end.json に登録されたボスか確認)
                    // ==========================================
                    if (plugin.getEndGameManager() != null) {
                        plugin.getEndGameManager().checkAndTriggerEnd(mobId);
                    }
                }
                break;
            }
        }
    }

    // ==========================================
    // ★追加: フロアボス/フィールドボスのレイド化対応。
    // ボス部屋にいる全員に、ダメージ割合に応じた倍率でEXP/Colを個別に渡し、
    // ドロップもmobs.jsonの確率を元に一人一人抽選して個別配布する。
    // ラストアタック(トドメ)を刺したプレイヤーは、各ドロップ確率に+10%される(100%上限)。
    // ==========================================
    private void handleBossRewardsAndDrops(LivingEntity bossEntity, String mobId, SAOMobData mobData, Player killer) {
        java.util.UUID bossUuid = bossEntity.getUniqueId();
        java.util.List<Player> participants = plugin.getBossManager().getPlayersInBossRoom(mobId);

        // ★バグ対策: 何らかの理由で「部屋の中」判定に誰も引っかからなかった場合でも、
        // 実際にトドメを刺したプレイヤーには最低限報酬を渡す(無報酬を防ぐ保険)。
        if (participants.isEmpty() && killer != null) {
            participants = new java.util.ArrayList<>();
            participants.add(killer);
        }

        // ★追加(実績): ボス討伐を記録する。ボス部屋に自分しかいなければソロ討伐として扱う。
        if (plugin.getAchievementManager() != null) {
            boolean soloKill = participants.size() == 1;
            for (Player ap : participants) {
                if (ap != null && ap.isOnline()) plugin.getAchievementManager().onBossKill(ap, mobId, soloKill);
            }
        }

        // ★変更: exp/colの「基礎値」はmobs.jsonの範囲([exp,expMax]/[col,colMax])から1回だけ抽選し、
        // 参加者全員で同じ基礎値を共有する(個々の取り分はダメージ割合倍率で変わる)。
        int baseExp = mobData.rollExp();
        long baseCol = mobData.rollCol();

        for (Player p : participants) {
            if (p == null || !p.isOnline()) continue;
            PlayerData stats = plugin.getPlayerManager().getStats(p);
            if (stats == null) continue;

            // ★バグ対策: maxHealthが0以下(データ不備)の場合のゼロ除算を防ぐ
            double maxHealth = mobData.maxHealth > 0 ? mobData.maxHealth : 1.0;
            double playerDamage = plugin.getRaidManager().getPlayerDamage(bossUuid, p.getUniqueId());
            double percent = Math.max(0.0, Math.min(1.0, playerDamage / maxHealth));

            double multiplier = getBossRewardMultiplier(percent);

            int finalExp = (int) Math.round(baseExp * multiplier);
            long finalCol = Math.round(baseCol * multiplier);

            // ==========================================
            // ★追加: 通常モブと同じ「格下補正」をボス討伐報酬にも適用する。
            // 自分のレベルがボスより高い場合、レベル差5ごとにexp・colを10%減少させる。
            // ただし最終額は最低5を保証する。代わりにドロップ率に+20%のボーナスが付く(下のtally処理側)。
            // ==========================================
            boolean isUnderleveled = stats.level > mobData.level;
            if (isUnderleveled) {
                int diff = stats.level - mobData.level;
                double reductionMultiplier = (diff / 5) * 0.1;
                double levelMultiplier = Math.max(0.0, 1.0 - reductionMultiplier);

                finalExp = Math.max(5, (int) Math.round(finalExp * levelMultiplier));
                finalCol = Math.max(5L, Math.round(finalCol * levelMultiplier));

                if (reductionMultiplier > 0) {
                    p.sendMessage("§7[System] 格下補正によりEXP・Colが " + (int) Math.round(Math.min(1.0, reductionMultiplier) * 100) + "% 減少しました。(代わりにドロップ率+20%)");
                }
            }

            stats.exp += finalExp;
            stats.col += finalCol;
            processLevelUp(p, stats);

            if (stats.currentQuestId != null && !stats.currentQuestId.isEmpty()) {
                sac.plugin.info.SACplugin.npc.SAOQuestData quest = plugin.getQuestManager().getQuest(stats.currentQuestId);
                if (quest != null && "MOB".equals(quest.type) && quest.targetId.equals(mobId)) {
                    if (!stats.questCompleted) {
                        stats.questProgress++;
                        String targetName = mobData.name != null ? mobData.name : mobId;
                        p.sendMessage("§b[System] " + targetName + " 討伐: " + stats.questProgress + "/" + quest.requiredAmount);

                        if (stats.questProgress >= quest.requiredAmount) {
                            stats.questCompleted = true;
                            p.sendMessage("§b[System] §r§aクエスト目標達成！NPCに報告してください。");
                            p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
                        }
                    }
                }
            }

            plugin.getSaoScoreboard().updateScoreboard(p, stats);
            plugin.getPlayerManager().savePlayerData(p, stats);

            // ★ドロップ: 部屋の全員に個別抽選。ラストアタックは各行の確率+10%(100%上限)。
            // ★変更: インベントリに空きがある場合のみ付与する(空きが無ければ何もしない)
            int itemsGiven = 0;
            if (mobData.drops != null) {
                boolean isLastHit = killer != null && killer.getUniqueId().equals(p.getUniqueId());
                double voteMultiplier = plugin.getVoteManager().getDropRateMultiplier(p);
                // ==========================================
                // ★追加: 格下狩り(+0.2)と投票バフの上乗せ分は「加算」で合算する(通常モブと同じ方式)。
                // 例: 格下狩り(1.2倍)+投票バフ(2.0倍) → 1.0 + 0.2 + 1.0 = 2.2倍
                // ==========================================
                double bonusTotal = (isUnderleveled ? 0.2 : 0.0) + Math.max(0.0, voteMultiplier - 1.0);
                double totalDropMultiplier = 1.0 + bonusTotal;

                // ★変更: 同じIDが複数回登録されている場合、各行を独立抽選して個数を積み上げる
                java.util.Map<String, Integer> tally = new java.util.LinkedHashMap<>();
                for (SAOMobData.DropEntry entry : mobData.drops) {
                    if (entry == null || entry.id == null) continue;
                    double chance = isLastHit ? Math.min(1.0, entry.chance + 0.10) : entry.chance;
                    chance = Math.min(1.0, chance * totalDropMultiplier);
                    if (Math.random() >= chance) continue;
                    tally.merge(entry.id, 1, Integer::sum);
                }

                for (Map.Entry<String, Integer> tallyEntry : tally.entrySet()) {
                    ItemStack dropItem = resolveDropItem(tallyEntry.getKey());
                    if (dropItem == null) continue;
                    int amount = tallyEntry.getValue();
                    dropItem.setAmount(amount);

                    java.util.HashMap<Integer, ItemStack> leftover = p.getInventory().addItem(dropItem);
                    int leftoverAmount = leftover.values().stream().mapToInt(ItemStack::getAmount).sum();
                    itemsGiven += (amount - leftoverAmount);
                }
            }

            // ★変更: チャットでの「+EXP +Col」表示を廃止し、各プレイヤーの現在地に
            // 2秒間だけ表示される小さめのテキストディスプレイに置き換える。
            spawnRewardDisplay(p.getLocation(), finalExp, finalCol, itemsGiven);
        }

        // ★このボス個体のダメージ記録は討伐処理完了後に必ずクリアする(メモリリーク防止・次個体との混同防止)
        plugin.getRaidManager().clearBossDamage(bossUuid);
    }

    private double getBossRewardMultiplier(double percent) {
        if (percent >= 0.70) return 1.4;
        if (percent >= 0.50) return 1.2;
        if (percent >= 0.30) return 1.0;
        if (percent >= 0.20) return 0.8;
        if (percent >= 0.10) return 0.6;
        return 0.4;
    }

    private ItemStack resolveDropItem(String itemId) {
        SAOWeapon weapon = plugin.getSaoItemManager().getWeapon(itemId);
        if (weapon != null) return plugin.getSaoItemManager().createWeaponItem(weapon);

        sac.plugin.info.SACplugin.item.SAOArmor armor = plugin.getSaoItemManager().getArmor(itemId);
        if (armor != null) return plugin.getSaoItemManager().createArmorItem(armor);

        SAOGeneralItemData genItem = plugin.getSaoItemManager().getGeneralItem(itemId);
        if (genItem != null) return plugin.getSaoItemManager().createGeneralItem(genItem);

        return null;
    }

    // ==========================================
    // ★修正: レベルアップに必要なEXPを二次関数(マゾ仕様)に変更
    // ★変更: 係数を50→75に変更(「直前の必要経験値 + 150×(レベル-1)」の閉じた式)
    // ==========================================
    public void processLevelUp(Player player, PlayerData stats) { // ← private を public に変更！

        // ★変更: 10レベルごとに必要経験値+500を追加
        int nextLevelExp = 500 + (stats.level * (stats.level - 1) * 75) + (stats.level / 10) * 500;
        int maxLvl = plugin.getLimitManager().getMaxPlayerLevel(); // ★追加

        while (stats.exp >= nextLevelExp) {
            // ★追加: 上限に達していたら経験値をリセットしてレベルアップをストップ
            if (stats.level >= maxLvl) {
                stats.exp = 0;
                break;
            }
            stats.level++;
            stats.exp -= nextLevelExp;
            stats.skillPoint += 1;

            // ★変更: 10レベルごとに必要経験値+500を追加
            nextLevelExp = 500 + (stats.level * (stats.level - 1) * 75) + (stats.level / 10) * 500;

            plugin.getPlayerManager().syncHealth(player, stats);
            stats.currentHP = stats.maxHP;
            player.setHealth(20.0);
            player.sendTitle("§bLEVEL UP!", "§fLevel " + stats.level, 10, 40, 10);
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
            player.sendMessage("§b[System] §r§bSkill Point +1");
            player.sendMessage("§b[System] §r『スキルポイントは Menu から Stats で割り振れます』");
        }
    }
    @EventHandler
    public void onMobEnvironmentDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity)) return;
        LivingEntity entity = (LivingEntity) event.getEntity();

        // SAOモブかチェック
        boolean isSaoMob = false;
        for (String tag : entity.getScoreboardTags()) {
            if (tag.startsWith("sao_mob_")) {
                isSaoMob = true;
                break;
            }
        }
        if (!isSaoMob) return;

        // ジャンプ攻撃中につく「sao_no_falldamage」タグがあれば、落下ダメージを無効化！
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL) {
            if (entity.getScoreboardTags().contains("sao_no_falldamage")) {
                event.setCancelled(true);
            }
        }
    }

    // ==========================================
    // ★追加: 通常モブのドロップID→実際のItemStackへの解決(武器→防具→一般アイテムの順で検索)
    // ==========================================
    private ItemStack resolveDropItemForNormalMob(String itemId) {
        SAOWeapon weapon = plugin.getSaoItemManager().getWeapon(itemId);
        if (weapon != null) return plugin.getSaoItemManager().createWeaponItem(weapon);

        sac.plugin.info.SACplugin.item.SAOArmor armor = plugin.getSaoItemManager().getArmor(itemId);
        if (armor != null) return plugin.getSaoItemManager().createArmorItem(armor);

        SAOGeneralItemData genItem = plugin.getSaoItemManager().getGeneralItem(itemId);
        if (genItem != null) return plugin.getSaoItemManager().createGeneralItem(genItem);

        return null;
    }

    // ==========================================
    // ★追加: 討伐報酬(Exp/Col/Items)を、討伐地点に2秒間だけ表示される
    // 小さめのテキストディスプレイとして表示する(チャット表示の代替)。
    // ==========================================
    private void spawnRewardDisplay(org.bukkit.Location loc, int exp, long col, int itemsGiven) {
        org.bukkit.Location displayLoc = loc.clone().add(0, 0.5, 0);
        org.bukkit.entity.TextDisplay display = (org.bukkit.entity.TextDisplay) displayLoc.getWorld()
                .spawnEntity(displayLoc, org.bukkit.entity.EntityType.TEXT_DISPLAY);

        String text = "§8Result\n"
                + "§8Exp     §8" + exp + "\n"
                + "§8Col     §8" + col + "\n"
                + "§8Items   §8" + itemsGiven;

        display.setText(text);
        display.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
        display.setAlignment(org.bukkit.entity.TextDisplay.TextAlignment.CENTER);
        display.setSeeThrough(true);
        display.setShadowed(false);
        display.setDefaultBackground(false);
        // ★変更: 背景を白に(アルファ値を下げれば半透明にもできます)
        display.setBackgroundColor(org.bukkit.Color.fromARGB(200, 255, 255, 255));

        // ★小さめに表示するため、通常の0.5倍サイズに縮小する
        display.setTransformation(new org.bukkit.util.Transformation(
                new org.joml.Vector3f(0, 0, 0), new org.joml.AxisAngle4f(0f, 0f, 1f, 0f),
                new org.joml.Vector3f(0.7f, 0.7f, 0.7f), new org.joml.AxisAngle4f(0f, 0f, 1f, 0f)
        ));

        // ★7秒(140tick)後に自動的に消す
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!display.isDead()) display.remove();
        }, 100L);
    }
}