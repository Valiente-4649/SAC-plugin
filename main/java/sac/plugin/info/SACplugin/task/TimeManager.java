package sac.plugin.info.SACplugin.task;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitRunnable;
import sac.plugin.info.SACplugin.SACplugin;

public class TimeManager extends BukkitRunnable {
    private final SACplugin plugin;

    // ★仕様: ゲーム内1日(24時間)を現実3時間(216,000 tick)で1周させる
    private final long TOTAL_DURATION_TICKS = 216000L;

    // ★仕様: 4:00に日の出、18:00に日の入り（昼14時間・夜10時間）
    private static final double SUNRISE_HOUR = 4.0;
    private static final double SUNSET_HOUR = 18.0;

    private double currentTick = 0;
    private long lastMcTime = -1;
    private String lastGameTime = "";

    // ★追加: 直前に日の出/日の入りの切り替わりを検知したか（連続発火防止）
    private boolean wasDay = true;

    public TimeManager(SACplugin plugin) {
        this.plugin = plugin;

        // 全ワールドのバニラの時間進行を念のため強制ストップする
        for (World world : Bukkit.getWorlds()) {
            world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        }

        // サーバー起動時に、今のマイクラ時間に合わせて時計の針(currentTick)を合わせる
        // (これでサーバーを再起動しても時間が巻き戻りません！)
        World mainWorld = Bukkit.getWorlds().get(0);
        long currentMcTime = mainWorld.getTime();
        double gameHour = mcTimeToGameHour(currentMcTime);
        currentTick = (long) ((gameHour / 24.0) * TOTAL_DURATION_TICKS);
        wasDay = isDaytime(gameHour);
    }

    /**
     * 現実時間のcurrentTickからゲーム内時刻(0.0〜24.0時)を計算する
     */
    public double getGameHour() {
        return ((double) currentTick / TOTAL_DURATION_TICKS) * 24.0;
    }

    /**
     * 現実時間のcurrentTickからゲーム内時刻を計算して "HH:MM" 形式で返す。
     */
    public String getGameTimeString() {
        double gameHour = getGameHour();
        int totalMinutes = (int) (gameHour * 60);
        int roundedMinutes = (totalMinutes / 10) * 10;
        int hour = (roundedMinutes / 60) % 24;
        int minute = roundedMinutes % 60;
        return String.format("%02d:%02d", hour, minute);
    }

    /**
     * ゲーム内時刻(0.0〜24.0時)から、現在が昼間(日の出〜日の入りの間)かどうかを返す
     */
    private boolean isDaytime(double gameHour) {
        return gameHour >= SUNRISE_HOUR && gameHour < SUNSET_HOUR;
    }

    /**
     * ★中核ロジック: ゲーム内時刻(0.0〜24.0時)をマイクラ内部時刻(0〜24000)に変換する。
     * マイクラの内部時刻は 0=朝6:00, 6000=正午, 12000=日没開始(18:00), 18000=深夜(0:00), 23999=夜明け直前 という対応。
     * これをベースに、SAO仕様の「4:00日の出・18:00日の入り」になるよう非線形にマッピングする。
     *
     * 区間ごとに直線補間する:
     *   ゲーム内 4:00(日の出)   → マイクラ   0 (朝6:00相当の明るさ)
     *   ゲーム内 18:00(日の入り) → マイクラ 12000 (日没開始)
     *   ゲーム内 24:00/0:00      → マイクラ 18000 (深夜)
     *   ゲーム内 4:00(翌日の日の出) → マイクラ 24000(=0, 夜明け前)
     */
    private long gameHourToMcTime(double gameHour) {
        // 0:00〜4:00 (夜明け前) と 18:00〜24:00 (日没後〜深夜) を「夜帯」としてひと続きに扱うため、
        // 4:00を起点に0〜24の範囲にシフトする
        double shifted = gameHour - SUNRISE_HOUR;
        if (shifted < 0) shifted += 24.0;
        // shifted: 0.0 = 日の出(4:00), 14.0 = 日の入り(18:00), 24.0 = 翌日の日の出(4:00)

        double dayLength = SUNSET_HOUR - SUNRISE_HOUR;       // 14時間
        double nightLength = 24.0 - dayLength;               // 10時間

        long mcTime;
        if (shifted < dayLength) {
            // 昼帯: マイクラ時刻 0 〜 12000 にマッピング
            double progress = shifted / dayLength;
            mcTime = (long) (progress * 12000.0);
        } else {
            // 夜帯: マイクラ時刻 12000 〜 24000(=0) にマッピング
            double nightProgress = (shifted - dayLength) / nightLength;
            mcTime = 12000L + (long) (nightProgress * 12000.0);
        }
        return mcTime % 24000L;
    }

    /** マイクラ内部時刻からゲーム内時刻(0.0〜24.0時)を逆算する（サーバー再起動時の同期用） */
    private double mcTimeToGameHour(long mcTime) {
        double dayLength = SUNSET_HOUR - SUNRISE_HOUR;
        double nightLength = 24.0 - dayLength;
        double shifted;
        if (mcTime < 12000) {
            double progress = (double) mcTime / 12000.0;
            shifted = progress * dayLength;
        } else {
            double nightProgress = (double) (mcTime - 12000) / 12000.0;
            shifted = dayLength + nightProgress * nightLength;
        }
        double gameHour = shifted + SUNRISE_HOUR;
        return gameHour % 24.0;
    }

    /**
     * ★追加: コマンド等から現在時刻を直接設定する。
     * @param hour 0.0〜24.0 のゲーム内時刻
     */
    public void setGameHour(double hour) {
        hour = ((hour % 24.0) + 24.0) % 24.0; // 0〜24の範囲に正規化
        currentTick = (long) ((hour / 24.0) * TOTAL_DURATION_TICKS);

        // ★即座にワールドの時間・空の状態を反映する
        long mcTime = gameHourToMcTime(hour);
        for (World world : Bukkit.getWorlds()) {
            world.setTime(mcTime);
        }
        lastMcTime = mcTime;
        wasDay = isDaytime(hour);

        // スコアボードも即時更新
        lastGameTime = "";
        updateAllScoreboards();
    }

    @Override
    public void run() {
        // 日中14時間、夜10時間。夜間のゲーム内時刻は日中の2倍の速度で進む。
        // 1日を実時間3時間に保つため、日中は19/24、夜間は38/24仮想tick進める。
        currentTick += isDaytime(getGameHour()) ? 19.0 / 24.0 : 38.0 / 24.0;

        // 1日経ったら朝にリセット
        if (currentTick >= TOTAL_DURATION_TICKS) {
            currentTick %= TOTAL_DURATION_TICKS;
        }

        double gameHour = getGameHour();
        long mcTime = gameHourToMcTime(gameHour);

        // マイクラ時間に変化があった時だけワールドの時間を更新する (処理を軽くするため)
        if (mcTime != lastMcTime) {
            for (World world : Bukkit.getWorlds()) {
                world.setTime(mcTime);
            }
            lastMcTime = mcTime;
        }

        // 日の出/日の入りの切り替わりを検知して、農業の成長処理を実行する
        // ★修正: 固定tick(旧仕様の昼2時間/夜1時間)ではなく、実際の日の出(4:00)・日の入り(18:00)に同期させる
        boolean isDay = isDaytime(gameHour);
        if (isDay != wasDay) {
            wasDay = isDay;
            if (plugin.getFarmManager() != null) {
                // isDay==true への切り替わり = 日の出（4:00） → 朝の成長処理
                // isDay==false への切り替わり = 日の入り（18:00） → 夕方の成長処理
                plugin.getFarmManager().executeGrowthLogicPublic(isDay);
            }
        }

        // ゲーム内時刻が10分進んだらスコアボードを更新する
        String newGameTime = getGameTimeString();
        if (!newGameTime.equals(lastGameTime)) {
            lastGameTime = newGameTime;
            updateAllScoreboards();
        }
    }

    private void updateAllScoreboards() {
        for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
            sac.plugin.info.SACplugin.Player.PlayerData data =
                    plugin.getPlayerManager().getStats(player);
            if (data != null) {
                plugin.getSaoScoreboard().updateScoreboard(player, data);
            }
        }
    }
}
