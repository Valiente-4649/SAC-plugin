package sac.plugin.info.SACplugin.vote;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.bukkit.entity.Player;
import sac.plugin.info.SACplugin.SACplugin;
import sac.plugin.info.SACplugin.util.Reloadable;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ★新規: 投票報酬(15分間ドロップ率アップ)を管理する。
 * - vote_config.jsonから投票サイトURL・バフ内容を読み込む
 * - オンライン中に投票が来たらその場でバフ付与
 * - オフライン中の投票は保留し、次回ログイン時に自動付与する(pending_votes.json)
 */
public class VoteManager implements Reloadable {
    private final SACplugin plugin;

    private String siteName = "投票サイト";
    private String voteUrl = "";
    private long buffDurationMillis = 15 * 60 * 1000L;
    private double buffMultiplier = 1.3;

    private final Map<UUID, Long> buffUntil = new HashMap<>();
    private final Set<String> pendingVotes = new HashSet<>();

    public VoteManager(SACplugin plugin) {
        this.plugin = plugin;
        loadConfig();
        loadPendingVotes();
    }

    public void loadConfig() {
        File file = new File(plugin.getDataFolder(), "vote_config.json");
        if (!file.exists()) plugin.saveResource("vote_config.json", false);

        try (FileReader reader = new FileReader(file)) {
            JsonObject json = new Gson().fromJson(reader, JsonObject.class);
            if (json != null) {
                if (json.has("siteName")) siteName = json.get("siteName").getAsString();
                if (json.has("voteUrl")) voteUrl = json.get("voteUrl").getAsString();
                if (json.has("buffDurationSeconds")) buffDurationMillis = json.get("buffDurationSeconds").getAsLong() * 1000L;
                if (json.has("buffMultiplier")) buffMultiplier = json.get("buffMultiplier").getAsDouble();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void reload() {
        loadConfig();
    }

    public String getSiteName() { return siteName; }
    public String getVoteUrl() { return voteUrl; }

    private File pendingFile() {
        return new File(plugin.getDataFolder(), "pending_votes.json");
    }

    private void loadPendingVotes() {
        File file = pendingFile();
        if (!file.exists()) return;
        try (FileReader reader = new FileReader(file)) {
            java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<java.util.List<String>>(){}.getType();
            java.util.List<String> loaded = new Gson().fromJson(reader, type);
            if (loaded != null) pendingVotes.addAll(loaded);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void savePendingVotes() {
        try (FileWriter writer = new FileWriter(pendingFile())) {
            new Gson().toJson(new java.util.ArrayList<>(pendingVotes), writer);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** オフライン中に投票されたプレイヤー名を保留リストに記録する */
    public void markPendingVote(String playerName) {
        pendingVotes.add(playerName);
        savePendingVotes();
    }

    /** 保留中の投票があるかどうか */
    public boolean hasPendingVote(String playerName) {
        return pendingVotes.contains(playerName);
    }

    /** 保留中の投票を消費(付与済みにする) */
    public void clearPendingVote(String playerName) {
        if (pendingVotes.remove(playerName)) savePendingVotes();
    }

    public void applyDropBuff(Player player) {
        UUID uuid = player.getUniqueId();
        long newUntil = System.currentTimeMillis() + buffDurationMillis;
        Long current = buffUntil.get(uuid);
        if (current != null && current > newUntil) return;
        buffUntil.put(uuid, newUntil);
    }

    /** 現在有効なドロップ率倍率(バフが無ければ1.0) */
    public double getDropRateMultiplier(Player player) {
        if (player == null) return 1.0;
        Long until = buffUntil.get(player.getUniqueId());
        if (until == null || System.currentTimeMillis() >= until) return 1.0;
        return buffMultiplier;
    }

    public boolean hasActiveBuff(Player player) {
        return getDropRateMultiplier(player) > 1.0;
    }
}
