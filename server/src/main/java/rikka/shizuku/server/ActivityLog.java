package rikka.shizuku.server;

import android.os.Parcel;
import android.util.AtomicFile;

import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import rikka.shizuku.server.ktx.HandlerKt;
import rikka.shizuku.server.util.Logger;

/**
 * Record of what clients did through Shizuku. It is limited to {@link #MAX_ENTRIES} entries and
 * saved to a file, so that it survives a restart of the service. Repeated identical calls are merged
 * into one entry with a counter, so that apps making thousands of binder calls do not flood it.
 */
public class ActivityLog {

    public static final int MAX_ENTRIES = 300;

    /** How many of the newest entries are searched for an identical entry to merge with. */
    private static final int MERGE_WINDOW = 30;

    public static class Entry {
        @SerializedName("time")
        public long time;
        @SerializedName("uid")
        public int uid;
        @SerializedName("package")
        public String packageName;
        @SerializedName("type")
        public int type;
        @SerializedName("detail")
        public String detail;
        @SerializedName("denied")
        public boolean denied;
        @SerializedName("count")
        public int count;
    }

    private static final Logger LOGGER = new Logger("ActivityLog");
    private static final Gson GSON = new Gson();
    private static final long WRITE_DELAY = 30 * 1000;
    private static final AtomicFile FILE =
            new AtomicFile(new File("/data/user_de/0/com.android.shell/shizuku_activity.json"));

    private final ArrayList<Entry> entries = new ArrayList<>();

    private final Runnable writeRunner = this::write;

    /**
     * Reads the log that was saved by an earlier run of the service.
     */
    public synchronized void load() {
        try (FileInputStream stream = FILE.openRead()) {
            Entry[] saved = GSON.fromJson(new InputStreamReader(stream), Entry[].class);
            if (saved != null) {
                entries.clear();
                for (Entry e : saved) {
                    if (e != null) entries.add(e);
                }
                while (entries.size() > MAX_ENTRIES) {
                    entries.remove(0);
                }
            }
        } catch (FileNotFoundException e) {
            // First run
        } catch (Throwable tr) {
            LOGGER.w(tr, "load activity log");
        }
    }

    private void scheduleWrite() {
        // One write for all the changes of the next half minute
        HandlerKt.getWorkerHandler().removeCallbacks(writeRunner);
        HandlerKt.getWorkerHandler().postDelayed(writeRunner, WRITE_DELAY);
    }

    private void write() {
        String json;
        synchronized (this) {
            json = GSON.toJson(entries.toArray(new Entry[0]));
        }
        synchronized (FILE) {
            FileOutputStream stream = null;
            try {
                stream = FILE.startWrite();
                stream.write(json.getBytes());
                FILE.finishWrite(stream);
            } catch (Throwable tr) {
                LOGGER.w(tr, "save activity log");
                if (stream != null) {
                    FILE.failWrite(stream);
                }
            }
        }
    }

    public synchronized void add(int uid, @Nullable String packageName, int type, @Nullable String detail, boolean denied) {
        long now = System.currentTimeMillis();
        int size = entries.size();
        for (int i = size - 1; i >= 0 && i >= size - MERGE_WINDOW; i--) {
            Entry e = entries.get(i);
            if (e.uid == uid && e.type == type && e.denied == denied && Objects.equals(e.detail, detail)) {
                e.count++;
                e.time = now;
                if (i != size - 1) {
                    entries.remove(i);
                    entries.add(e);
                }
                scheduleWrite();
                return;
            }
        }

        Entry e = new Entry();
        e.time = now;
        e.uid = uid;
        e.packageName = packageName;
        e.type = type;
        e.detail = detail;
        e.denied = denied;
        e.count = 1;
        entries.add(e);
        if (entries.size() > MAX_ENTRIES) {
            entries.remove(0);
        }
        scheduleWrite();
    }

    public synchronized void clear() {
        entries.clear();
        scheduleWrite();
    }

    /**
     * Format: int count, then for each entry (newest first):
     * long time, int uid, String packageName, int type, String detail, int denied, int count.
     */
    public void writeToParcel(Parcel out) {
        List<Entry> copy;
        synchronized (this) {
            copy = new ArrayList<>(entries.size());
            for (Entry e : entries) {
                Entry c = new Entry();
                c.time = e.time;
                c.uid = e.uid;
                c.packageName = e.packageName;
                c.type = e.type;
                c.detail = e.detail;
                c.denied = e.denied;
                c.count = e.count;
                copy.add(c);
            }
        }
        out.writeInt(copy.size());
        for (int i = copy.size() - 1; i >= 0; i--) {
            Entry e = copy.get(i);
            out.writeLong(e.time);
            out.writeInt(e.uid);
            out.writeString(e.packageName);
            out.writeInt(e.type);
            out.writeString(e.detail);
            out.writeInt(e.denied ? 1 : 0);
            out.writeInt(e.count);
        }
    }
}
