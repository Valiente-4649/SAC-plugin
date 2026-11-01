package sac.plugin.info.SACplugin.BossAI;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import sac.plugin.info.SACplugin.SACplugin;
import sac.plugin.info.SACplugin.boss.BossData;
import sac.plugin.info.SACplugin.mob.SAOMobData;

public class NatoAI extends FloorBossAI {

    public NatoAI(SACplugin plugin, LivingEntity boss, SAOMobData mobData, BossData bossData) {
        super(plugin, boss, mobData, bossData);
        cooldownTick = 60; // 開幕3秒の猶予
    }

    @Override
    public void tick() {
        if (!isValid()) return;
        if (isCasting) return;
        if (getPlayersInRoom().isEmpty()) return;

        if (cooldownTick > 0) { cooldownTick--; return; }

        // ランダムにスキルを選択
        int skill = (int)(Math.random() * 2);
        boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 1f, 0.8f);

        if (skill == 0) {
            // スキル1: 円範囲
            castCircleAttack(10, 30, 2.0, null, findSkill("circle"));
        } else {
            // スキル2: 前方ジャンプ
            castJumpAttack(5, 5, 20, 1.5, null, findSkill("jump"));
        }
    }

    public void onDeath() {
        Location deathLoc = boss.getLocation().clone();
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
            broadcastToRoom("§4[BOSS] Nato the Colonel Taurus を討伐した！");
            broadcastToRoom("§4[BOSS] 将軍 『Baran the General Taurus』");
            plugin.getSaoMobManager().spawnMob("baran_the_general_taurus", deathLoc);
        }, 20L);
    }

    private void broadcastToRoom(String msg) {
        for (Player p : getPlayersInRoom()) p.sendMessage(msg);
    }
}