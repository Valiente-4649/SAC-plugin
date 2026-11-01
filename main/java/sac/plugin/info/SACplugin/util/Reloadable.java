package sac.plugin.info.SACplugin.util;

/**
 * JSON/YMLなど自前の設定ファイルを持つマネージャークラスが実装するインターフェース。
 * 実装して SACplugin#registerReloadable(this) で登録しておけば、
 * /sacreload コマンドが自動的にこの reload() を呼び出してくれるようになる。
 * これから新しいマネージャーを増やす時も、SACReloadCommand側は一切触らなくてよい。
 */
public interface Reloadable {
    /** 自分が持つJSON/YMLファイルを再読み込みする */
    void reload();

    /** /sacreload の結果表示に使う名前（未実装ならクラス名がそのまま使われる） */
    default String getReloadName() {
        return this.getClass().getSimpleName();
    }
}
