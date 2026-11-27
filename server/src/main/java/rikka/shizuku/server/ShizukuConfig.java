package rikka.shizuku.server;

import androidx.annotation.NonNull;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

public class ShizukuConfig {

    public static final int LATEST_VERSION = 2;

    @SerializedName("version")
    public int version = LATEST_VERSION;

    @SerializedName("packages")
    public List<PackageEntry> packages = new ArrayList<>();

    /** Days without use after which an app loses the permission, 0 for never. */
    @SerializedName("autoRevokeDays")
    public int autoRevokeDays = 0;

    public static class PackageEntry extends ConfigPackageEntry {

        @SerializedName("uid")
        public final int uid;

        @SerializedName("flags")
        public int flags;

        @SerializedName("packages")
        public List<String> packages;

        /** Time of the last privileged call, System.currentTimeMillis(), 0 if unknown. */
        @SerializedName("lastUsed")
        public long lastUsed;

        public PackageEntry(int uid, int flags) {
            this.uid = uid;
            this.flags = flags;
            this.packages = new ArrayList<>();
        }

        @Override
        public boolean isAllowed() {
            return (flags & ConfigManager.FLAG_ALLOWED) != 0;
        }

        @Override
        public boolean isDenied() {
            return (flags & ConfigManager.FLAG_DENIED) != 0;
        }
    }

    public ShizukuConfig() {
    }

    public ShizukuConfig(@NonNull List<PackageEntry> packages) {
        this.version = LATEST_VERSION;
        this.packages = packages;
    }
}
