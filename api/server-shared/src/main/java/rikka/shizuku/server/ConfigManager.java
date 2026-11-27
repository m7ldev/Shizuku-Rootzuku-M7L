package rikka.shizuku.server;

import androidx.annotation.Nullable;

import java.util.List;

import rikka.shizuku.server.util.Logger;

public abstract class ConfigManager {

    protected static final Logger LOGGER = new Logger("ConfigManager");

    public static final int FLAG_ALLOWED = 1 << 1;
    public static final int FLAG_DENIED = 1 << 2;
    public static final int MASK_PERMISSION = FLAG_ALLOWED | FLAG_DENIED;

    /** The app is not allowed to run commands (newProcess). */
    public static final int FLAG_DENY_SHELL = 1 << 3;
    /** The app is not allowed to start user services. */
    public static final int FLAG_DENY_USER_SERVICE = 1 << 4;
    /** The app is not allowed to call system services through Shizuku (transactRemote). */
    public static final int FLAG_DENY_BINDER = 1 << 5;
    public static final int MASK_RESTRICTION = FLAG_DENY_SHELL | FLAG_DENY_USER_SERVICE | FLAG_DENY_BINDER;

    @Nullable
    public abstract ConfigPackageEntry find(int uid);

    public abstract void update(int uid, List<String> packages, int mask, int values);

    public abstract void remove(int uid);
}
