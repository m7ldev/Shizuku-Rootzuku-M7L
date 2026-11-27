package rikka.shizuku.server;

public class ServerConstants {

    public static final int MANAGER_APP_NOT_FOUND = 50;

    public static final String PERMISSION = "moe.shizuku.manager.permission.API_V23";
    public static final String MANAGER_APPLICATION_ID = "com.m7l.shizuku";
    public static final String REQUEST_PERMISSION_ACTION = MANAGER_APPLICATION_ID + ".intent.action.REQUEST_PERMISSION";

    public static final int BINDER_TRANSACTION_getApplications = 10001;
    /** Since patch version 7. Manager only. */
    public static final int BINDER_TRANSACTION_getActivityLog = 10002;
    /** Since patch version 7. Manager only. */
    public static final int BINDER_TRANSACTION_clearActivityLog = 10003;

    /** Since patch version 8. Manager only. Argument: int days, 0 turns it off. */
    public static final int BINDER_TRANSACTION_setAutoRevokeDays = 10004;
    /** Since patch version 8. Manager only. */
    public static final int BINDER_TRANSACTION_getAutoRevokeDays = 10005;

    /** First server patch version with activity log, per-app restrictions and timed permission. */
    public static final int PATCH_VERSION_EXTRAS = 7;
}
