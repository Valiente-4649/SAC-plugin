package sac.plugin.info.SACplugin.BossAI;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import sac.plugin.info.SACplugin.SACplugin;
import sac.plugin.info.SACplugin.boss.BossData;
import sac.plugin.info.SACplugin.mob.SAOMobData;

public class BaranAI extends FloorBossAI {

    public BaranAI(SACplugin plugin, LivingEntity boss, SAOMobData mobData, BossData bossData) {
        super(plugin, boss, mobData, bossData);
        cooldownTick = 60;
    }

    @Override
    public void tick() {
        if (!isValid()) return;
        if (isCasting) return;
        if (getPlayersInRoom().isEmpty()) return;

        if (cooldownTick > 0) { cooldownTick--; return; }

        int skill = (int)(Math.random() * 3);
        boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 1f, 0.7f);

        if (skill == 0) {
            castCircleAttack(10, 30, 2.0, null, findSkill("circle"));
        } else if (skill == 1) {
            castJumpAttack(5, 5, 20, 1.5, null, findSkill("jump"));
        } else {
            Player nearest = getNearestPlayer();
            if (nearest != null) {
                castJumpToPlayer(nearest, 7, 25, 1.5, null, findSkill("jump_to_player"));
            } else {
                castCircleAttack(10, 30, 2.0, null, findSkill("circle"));
            }
        }
    }

    public void onDeath() {
        Location deathLoc = boss.getLocation().clone();
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
            broadcastToRoom("§4[BOSS] Baran the General Taurus を討伐した！");
            broadcastToRoom("§4[BOSS] 牛頭王『Asterios the Taurus King』");
            plugin.getSaoMobManager().spawnMob("asterios_the_taurus_king", deathLoc);
        }, 20L);
    }

    private void broadcastToRoom(String msg) {
        for (Player p : getPlayersInRoom()) p.sendMessage(msg);
    }
}