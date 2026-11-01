package sac.plugin.info.SACplugin;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import sac.plugin.info.SACplugin.util.Reloadable;

import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class EndGameManager implements Reloadable {
    private final SACplugin plugin;
    private final List<String> endBossIds = new ArrayList<>();
    private boolean isEnding = false; // 二重発動防止用フラグ

    public EndGameManager(SACplugin plugin) {
        this.plugin = plugin;
        loadEndBosses();
    }

    public void loadEndBosses() {
        File file = new File(plugin.getDataFolder(), "end.json");
        if (!file.exists()) plugin.saveResource("end.json", false);

        try (FileReader reader = new FileReader(file)) {
            Type type = new TypeToken<List<String>>(){}.getType();
            List<String> loaded = new Gson().fromJson(reader, type);
            if (loaded != null) {
                endBossIds.clear();
                endBossIds.addAll(loaded);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ボスが倒された時に呼ばれるメソッド
    public void checkAndTriggerEnd(String defeatedMobId) {
        if (isEnding) return; // 既に終了処理が始まっていたら何もしない

        // 倒した敵のIDが end.json に登録されているかチェック
        if (endBossIds.contains(defeatedMobId)) {
            isEnding = true;
            startEndSequence();
        }
    }

    @Override
    public void reload() {
        loadEndBosses();
    }

    // 劇的なゲームクリア演出とシャットダウン処理
    private void startEndSequence() {
        // 1. 瞬時に全プレイヤーへクリアを通知
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendTitle("§bGAME CLEARED", "§fSword Art Online has been cleared.", 10, 100, 20);
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
        }

        Bukkit.broadcastMessage("§b=======================================");
        Bukkit.broadcastMessage("§a[SYSTEM] 第一層ボスが討伐されました。");
        Bukkit.broadcastMessage("§a[SYSTEM] これより全プレイヤーを現実世界へログアウトさせます。");
        Bukkit.broadcastMessage("§b=======================================");

        // 2. 5秒待ってから、10秒間のカウントダウンを開始
        new BukkitRunnable() {
            int count = 10;

            @Override
            public void run() {
                if (count <= 0) {
                    // 3. カウント0で全プレイヤーをキックし、サーバーを停止
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        p.kickPlayer("§aテストクリアおめでとうございます！\n\n§fリンクアウトしました。お疲れ様でした！");
                    }
                    Bukkit.setWhitelist(true);
                    this.cancel();
                    return;
                }

                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.sendMessage("§b[System] §fログアウトまで... §e" + count + "秒");
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1.0f, 1.0f);
                }
                count--;
            }
        }.runTaskTimer(plugin, 100L, 20L); // 100Tick(5秒)遅らせてから、20Tick(1秒)ごとに実行
    }
}
