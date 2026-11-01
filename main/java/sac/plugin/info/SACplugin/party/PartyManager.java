package sac.plugin.info.SACplugin.party;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import sac.plugin.info.SACplugin.SACplugin;

import java.util.*;

public class PartyManager {
    private final SACplugin plugin;

    // プレイヤーがどのパーティー(リーダーのUUID)に属しているかを管理
    private final Map<UUID, UUID> playerPartyMap = new HashMap<>();

    // パーティーごとのメンバーリスト (リーダーUUID -> メンバーのUUIDリスト)
    private final Map<UUID, List<UUID>> partyMembers = new HashMap<>();

    // 招待のクールダウン管理 (招待された人 -> 招待した人 -> 有効期限)
    private final Map<UUID, Map<UUID, Long>> cooldowns = new HashMap<>();

    // 誰から招待されているかの保留データ (招待された人 -> 招待した人)
    private final Map<UUID, UUID> pendingInvites = new HashMap<>();

    private final Set<UUID> partyChatToggles = new HashSet<>();

    public PartyManager(SACplugin plugin) {
        this.plugin = plugin;
    }

    // パーティーに参加しているか確認
    public boolean isInParty(Player player) {
        return playerPartyMap.containsKey(player.getUniqueId());
    }

    // クールダウン中(5分以内)か確認
    public boolean isOnCooldown(Player target, Player inviter) {
        if (!cooldowns.containsKey(target.getUniqueId())) return false;
        Map<UUID, Long> targetCooldowns = cooldowns.get(target.getUniqueId());
        if (!targetCooldowns.containsKey(inviter.getUniqueId())) return false;

        // 現在の時間が、有効期限を過ぎていないか
        return System.currentTimeMillis() < targetCooldowns.get(inviter.getUniqueId());
    }

    // パーティー招待を送信する処理
    public void sendInvite(Player inviter, Player target) {
        UUID targetId = target.getUniqueId();
        UUID inviterId = inviter.getUniqueId();
        if (playerPartyMap.containsKey(inviterId)) {
            UUID leaderId = playerPartyMap.get(inviterId);
            if (partyMembers.get(leaderId).size() >= 6) {
                inviter.sendMessage("§b[System] §r§cパーティーが最大人数(6人)に達しているため、招待できません");
                return;
            }
        }
        // 保留データにセット
        pendingInvites.put(targetId, inviterId);

        // クールダウン(5分間)を一旦セット（「いいえ」を押した時にも使います）
        // 5分 = 5 * 60 * 1000 = 300,000ミリ秒
        cooldowns.putIfAbsent(targetId, new HashMap<>());
        cooldowns.get(targetId).put(inviterId, System.currentTimeMillis() + 300000);

        // 招待者にメッセージ
        inviter.sendMessage("§a" + target.getName() + " にパーティー招待を送信しました");

        // ターゲットにクリッカブルメッセージを送信
        target.sendMessage("§b=============================");
        target.sendMessage("§e" + inviter.getName() + " §aからパーティーに招待されました");
        target.sendMessage("§b[System] §r§f参加しますか？");

        // 「はい」ボタン
        TextComponent acceptBtn = new TextComponent("§a[はい] ");
        acceptBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/partyaccept " + inviter.getName()));

        // 「いいえ」ボタン
        TextComponent denyBtn = new TextComponent("§c[いいえ]");
        denyBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/partydeny " + inviter.getName()));

        target.spigot().sendMessage(acceptBtn, denyBtn);
        target.sendMessage("§b=============================");
    }

    // パーティー作成＆参加処理
    public void joinParty(Player target, Player leader) {
        UUID leaderId = leader.getUniqueId();
        UUID targetId = target.getUniqueId();

        // ==========================================
        // ★追加: すでにパーティーに入っている場合は、招待を受け入れられないようにする！
        // ==========================================
        if (isInParty(target)) {
            target.sendMessage("§b[System] §r§c既にパーティーに参加しているため、新たな招待を受け入れることはできません");
            pendingInvites.remove(targetId);
            return;
        }

        // ★追加: 参加する直前に人数を再確認
        if (playerPartyMap.containsKey(leaderId)) {
            UUID actualLeaderId = playerPartyMap.get(leaderId);
            if (partyMembers.get(actualLeaderId).size() >= 6) {
                target.sendMessage("§b[System] §r§cパーティーが満員(6人)のため参加できませんでした");
                leader.sendMessage("§b[System] §r§cパーティーが満員のため、" + target.getName() + " が参加できませんでした");
                pendingInvites.remove(targetId);
                return;
            }
        }
        // もしリーダーがまだパーティーを作っていなかったら、新しく作る
        if (!playerPartyMap.containsKey(leaderId)) {
            playerPartyMap.put(leaderId, leaderId);
            partyMembers.put(leaderId, new ArrayList<>(Arrays.asList(leaderId)));
        }

        // ターゲットをメンバーに追加
        UUID actualLeaderId = playerPartyMap.get(leaderId);
        playerPartyMap.put(targetId, actualLeaderId);
        partyMembers.get(actualLeaderId).add(targetId);

        // メッセージ送信
        leader.sendMessage("§a" + target.getName() + " がパーティーに参加しました");
        target.sendMessage("§a" + leader.getName() + " のパーティーに参加しました");

        // 招待保留リストから削除
        pendingInvites.remove(targetId);
    }

    // 招待拒否処理
    public void denyInvite(Player target, Player inviter) {
        target.sendMessage("§c" + inviter.getName() + " の招待を断りました。(5分間招待を受け付けません)");
        inviter.sendMessage("§c" + target.getName() + " にパーティー招待を断られました");
        pendingInvites.remove(target.getUniqueId());
    }

    public boolean hasPendingInviteFrom(Player target, Player inviter) {
        return pendingInvites.containsKey(target.getUniqueId()) && pendingInvites.get(target.getUniqueId()).equals(inviter.getUniqueId());
    }
    public List<UUID> getPartyMembers(Player player) {
        if (!isInParty(player)) return new ArrayList<>();
        UUID leaderId = playerPartyMap.get(player.getUniqueId());
        return partyMembers.get(leaderId);
    }

    // パーティーから抜ける処理
    public void leaveParty(Player player) {
        UUID playerId = player.getUniqueId();
        if (!playerPartyMap.containsKey(playerId)) return;

        UUID leaderId = playerPartyMap.get(playerId);
        List<UUID> members = partyMembers.get(leaderId);

        // リストから削除
        members.remove(playerId);
        playerPartyMap.remove(playerId);
        partyChatToggles.remove(playerId); // チャット設定もリセット

        player.sendMessage("§b[System] §r§aパーティーから脱退しました");

        if (members.isEmpty()) {
            // 誰もいなくなったらパーティー解散
            partyMembers.remove(leaderId);
        } else if (leaderId.equals(playerId)) {
            // リーダーが抜けた場合は、残った最初の人が新リーダーになる
            UUID newLeaderId = members.get(0);

            // ★【修正】新リーダーがオフラインでも名前を取得できるようにする（エラー防止）
            String newLeaderName = org.bukkit.Bukkit.getOfflinePlayer(newLeaderId).getName();
            if (newLeaderName == null) newLeaderName = "不明なプレイヤー";

            for (UUID memberId : members) {
                playerPartyMap.put(memberId, newLeaderId); // 全員のリーダー情報を更新
                Player p = Bukkit.getPlayer(memberId);
                if (p != null) p.sendMessage("§b[System] §r§eリーダーが抜けたため、" + newLeaderName + " が新リーダーになりました");
            }
            partyMembers.put(newLeaderId, members);
            partyMembers.remove(leaderId);
        } else {
            // 他のメンバーに通知
            for (UUID memberId : members) {
                Player p = Bukkit.getPlayer(memberId);
                if (p != null) p.sendMessage("§e" + player.getName() + " がパーティーから脱退しました");
            }
        }
    }

    // パーティーチャットの切り替え (オンならオフに、オフならオンに)
    public boolean togglePartyChat(Player player) {
        UUID uuid = player.getUniqueId();
        if (partyChatToggles.contains(uuid)) {
            partyChatToggles.remove(uuid);
            return false;
        } else {
            partyChatToggles.add(uuid);
            return true;
        }
    }

    // チャットがオンになっているか確認
    public boolean isPartyChatEnabled(Player player) {
        return partyChatToggles.contains(player.getUniqueId());
    }

    // パーティーメンバーだけにメッセージを送信
    public void sendPartyMessage(Player sender, String message) {
        UUID leaderId = playerPartyMap.get(sender.getUniqueId());
        if (leaderId == null) return;

        for (UUID memberId : partyMembers.get(leaderId)) {
            Player p = Bukkit.getPlayer(memberId);
            if (p != null) {
                p.sendMessage("§d[System] " + sender.getName() + ": §f" + message);
            }
        }
    }

    // パーティーごとのスイッチクールタイム (リーダーUUID -> 解除時間)
    private final Map<UUID, Long> partySwitchCooldowns = new HashMap<>();
    // パーティーごとの最後のスキル使用記録 (リーダーUUID -> 記録データ)
    private final Map<UUID, SkillRecord> lastSkillRecords = new HashMap<>();

    // スキル使用記録用のインナークラス
    private static class SkillRecord {
        UUID casterId;
        long castTime;
        org.bukkit.Location location;

        SkillRecord(UUID casterId, long castTime, org.bukkit.Location location) {
            this.casterId = casterId;
            this.castTime = castTime;
            this.location = location;
        }
    }

    // ① 経験値分配メソッド
    public void shareExp(Player killer, int mobLevel, int baseExp) {
        if (!isInParty(killer)) return;

        UUID leaderId = playerPartyMap.get(killer.getUniqueId());
        List<UUID> members = partyMembers.get(leaderId);
        if (members == null) return;

        // 40%の経験値を計算
        int sharedExp = (int) (baseExp * 0.5);
        if (sharedExp <= 0) return;

        sac.plugin.info.SACplugin.Player.PlayerManager pm = plugin.getPlayerManager();

        for (UUID memberId : members) {
            if (memberId.equals(killer.getUniqueId())) continue; // 倒した本人は別で100%貰うのでスキップ

            Player member = Bukkit.getPlayer(memberId);
            if (member != null && member.isOnline()) {

                // ★【追加】キラーと同じワールドで、かつ20ブロック以内の場合のみ分配する
                if (killer.getWorld() != member.getWorld() || killer.getLocation().distance(member.getLocation()) > 20.0) {
                    continue; // 遠すぎる場合はスキップ
                }

                sac.plugin.info.SACplugin.Player.PlayerData data = pm.getStats(member);
                if (data != null) {
                    // モブのレベルが自分のレベルより4以上高くないかチェック (差が3以下ならOK)
                    if (mobLevel - data.level <= 3) {
                        data.exp += sharedExp;
                        member.sendMessage("§e[System] §a" + killer.getName() + " により§e" + sharedExp + " EXP §aを獲得しました");
                        // ※必要に応じてここでレベルアップ判定処理を呼び出してください
                        pm.savePlayerData(member, data);
                    } else {
                        member.sendMessage("§c[System] 敵のレベルが高すぎるため、経験値が共有されませんでした");
                    }
                }
            }
        }
    }

    // ② スキル使用を記録するメソッド（プレイヤー1用）
    public void recordSkillCast(Player player) {
        if (!isInParty(player)) return;
        UUID leaderId = playerPartyMap.get(player.getUniqueId());
        lastSkillRecords.put(leaderId, new SkillRecord(player.getUniqueId(), System.currentTimeMillis(), player.getLocation()));
    }

    // ③ スイッチを発動するメソッド（プレイヤー2用）
    public boolean trySwitch(Player player2) {
        if (!isInParty(player2)) return false;
        UUID leaderId = playerPartyMap.get(player2.getUniqueId());

        // パーティー全体が15秒のスイッチクールダウン中かチェック
        if (partySwitchCooldowns.containsKey(leaderId) && System.currentTimeMillis() < partySwitchCooldowns.get(leaderId)) {
            return false;
        }

        SkillRecord lastRecord = lastSkillRecords.get(leaderId);
        if (lastRecord == null) return false;

        // 同じプレイヤー同士ではスイッチできない
        if (lastRecord.casterId.equals(player2.getUniqueId())) return false;

        // ★【変更】5秒(5000ミリ秒)以内かチェック
        if (System.currentTimeMillis() - lastRecord.castTime > 3000) return false;

        Player player1 = Bukkit.getPlayer(lastRecord.casterId);
        if (player1 == null || !player1.isOnline()) return false;

        // ★【変更】10ブロック以内かチェック
        if (player1.getWorld() != player2.getWorld() || player1.getLocation().distance(player2.getLocation()) > 10.0) {
            return false;
        }

        // --- スイッチ発動！ ---
        // 1. パーティー全体に15秒(15000ミリ秒)のクールタイムを設定
        partySwitchCooldowns.put(leaderId, System.currentTimeMillis() + 15000);

        // 2. 記録を消去（連続スイッチ防止）
        lastSkillRecords.remove(leaderId);

        // 3. プレイヤー1のクールタイムをカット
        plugin.getSkillListener().resetCooldown(player1);

        // スイッチ成功時に「次に撃つスキルの威力+1.0」のバフを付与する！
        plugin.getSkillListener().addSwitchBuff(player1.getUniqueId());

        // 4. ド派手なエフェクトと通知
        sendPartyMessage(player1, "§bSWITCH!! §e" + player1.getName() + " §f=> §e" + player2.getName());
        player1.playSound(player1.getLocation(), org.bukkit.Sound.BLOCK_ANVIL_USE, 1.0f, 2.0f);
        player2.playSound(player2.getLocation(), org.bukkit.Sound.BLOCK_ANVIL_USE, 1.0f, 2.0f);

        return true;
    }
    // ==========================================
    // ★追加: オンラインのパーティーメンバーリストを取得する便利メソッド
    // ==========================================
    public List<Player> getOnlinePartyMembers(Player player) {
        List<Player> onlineMembers = new ArrayList<>();
        UUID leaderId = playerPartyMap.get(player.getUniqueId());
        if (leaderId == null) return onlineMembers;

        List<UUID> memberIds = partyMembers.get(leaderId);
        if (memberIds != null) {
            for (UUID id : memberIds) {
                Player p = Bukkit.getPlayer(id);
                if (p != null && p.isOnline()) {
                    onlineMembers.add(p);
                }
            }
        }
        return onlineMembers;
    }
}
