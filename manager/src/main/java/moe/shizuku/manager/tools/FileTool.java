package moe.shizuku.manager.tools;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * File operations that the shell has no command for: zip archives, ISO images and splitting
 * files. The file manager runs this class with app_process through the Shizuku service, so it
 * works with the permissions of the service and not with those of the app.
 * <p>
 * Only classes of the Java library are used here. Listings are printed as lines of
 * {@code type|size|seconds|path}, where type is d or f.
 */
public class FileTool {

    private static final int BUFFER = 1024 * 1024;

    private static PrintStream out;

    public static void main(String[] args) {
        try {
            out = new PrintStream(new FileOutputStream(java.io.FileDescriptor.out), false, "UTF-8");
            run(args);
            out.flush();
            System.exit(0);
        } catch (Throwable e) {
            String message = e.getMessage();
            System.err.println(message == null || message.isEmpty() ? e.toString() : message);
            System.exit(1);
        }
    }

    private static void run(String[] a) throws IOException {
        if (a.length == 0) throw new IOException("no command");
        switch (a[0]) {
            case "zip": {
                String[] names = new String[Math.max(0, a.length - 3)];
                System.arraycopy(a, 3, names, 0, names.length);
                zip(new File(a[1]), new File(a[2]), names);
                break;
            }
            case "ziplist":
                zipList(new File(a[1]));
                break;
            case "unzip":
                unzip(new File(a[1]), new File(a[2]), a.length > 3 ? a[3] : "");
                break;
            case "zipreplace":
                zipReplace(new File(a[1]), a[2], new File(a[3]));
                break;
            case "isolist":
                for (IsoEntry e : isoRead(new File(a[1]))) {
                    line(e.dir, e.size, e.time, e.path);
                }
                break;
            case "isoextract":
                isoExtract(new File(a[1]), new File(a[2]), a.length > 3 ? a[3] : "");
                break;
            case "split":
                split(new File(a[1]), Long.parseLong(a[2]));
                break;
            case "join":
                join(new File(a[1]));
                break;
            default:
                throw new IOException("unknown command " + a[0]);
        }
    }

    private static void line(boolean dir, long size, long seconds, String path) {
        out.println((dir ? "d" : "f") + "|" + size + "|" + seconds + "|" + path);
    }

    private static void copy(InputStream in, OutputStream to, long count) throws IOException {
        byte[] buffer = new byte[BUFFER];
        long left = count;
        while (left != 0) {
            int want = left < 0 ? buffer.length : (int) Math.min(buffer.length, left);
            int n = in.read(buffer, 0, want);
            if (n < 0) {
                if (left > 0) throw new IOException("unexpected end of file");
                break;
            }
            to.write(buffer, 0, n);
            if (left > 0) left -= n;
        }
    }

    /**
     * The target of an entry inside {@code dest}. Entries that would land outside of it, for
     * example with ".." in their name, are refused.
     */
    private static File target(File dest, String relative) throws IOException {
        File file = new File(dest, relative);
        String root = dest.getCanonicalPath();
        String path = file.getCanonicalPath();
        if (!path.equals(root) && !path.startsWith(root + File.separator)) {
            throw new IOException("unsafe entry name: " + relative);
        }
        return file;
    }

    /**
     * What is left of {@code path} when the entry {@code prefix} is extracted: the name of the
     * entry itself and everything below it. Null when the path is not part of the entry.
     */
    private static String relative(String path, String prefix) {
        if (prefix.isEmpty()) return path;
        if (!path.equals(prefix) && !path.startsWith(prefix + "/")) return null;
        int cut = prefix.lastIndexOf('/');
        return cut < 0 ? path : path.substring(cut + 1);
    }

    private static String trimSlashes(String path) {
        int start = 0;
        int end = path.length();
        while (start < end && path.charAt(start) == '/') start++;
        while (end > start && path.charAt(end - 1) == '/') end--;
        return path.substring(start, end);
    }

    // ---- zip

    private static void zip(File output, File base, String[] names) throws IOException {
        if (output.exists()) throw new IOException(output.getName() + " already exists");
        if (names.length == 0) {
            names = base.list();
            if (names == null) throw new IOException("cannot read " + base);
        }
        String self = output.getCanonicalPath();
        boolean done = false;
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(output), BUFFER))) {
            for (String name : names) {
                zipAdd(zip, new File(base, name), name, self);
            }
            done = true;
        } finally {
            if (!done) output.delete();
        }
    }

    private static void zipAdd(ZipOutputStream zip, File file, String name, String self) throws IOException {
        if (file.getCanonicalPath().equals(self)) return;
        if (file.isDirectory()) {
            ZipEntry entry = new ZipEntry(name + "/");
            entry.setTime(file.lastModified());
            zip.putNextEntry(entry);
            zip.closeEntry();
            String[] children = file.list();
            if (children == null) throw new IOException("cannot read " + file);
            for (String child : children) {
                zipAdd(zip, new File(file, child), name + "/" + child, self);
            }
        } else {
            ZipEntry entry = new ZipEntry(name);
            entry.setTime(file.lastModified());
            zip.putNextEntry(entry);
            try (InputStream in = new FileInputStream(file)) {
                copy(in, zip, -1);
            }
            zip.closeEntry();
        }
    }

    private static void zipList(File archive) throws IOException {
        try (ZipFile zip = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = trimSlashes(entry.getName());
                if (name.isEmpty()) continue;
                line(entry.isDirectory(), Math.max(0, entry.getSize()), Math.max(0, entry.getTime() / 1000), name);
            }
        }
    }

    private static void unzip(File archive, File dest, String prefix) throws IOException {
        prefix = trimSlashes(prefix);
        int count = 0;
        try (ZipFile zip = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = trimSlashes(entry.getName());
                if (name.isEmpty()) continue;
                String relative = relative(name, prefix);
                if (relative == null) continue;
                File file = target(dest, relative);
                count++;
                if (entry.isDirectory()) {
                    file.mkdirs();
                    continue;
                }
                File parent = file.getParentFile();
                if (parent != null) parent.mkdirs();
                try (InputStream in = new BufferedInputStream(zip.getInputStream(entry));
                     OutputStream to = new FileOutputStream(file)) {
                    copy(in, to, -1);
                }
                if (entry.getTime() > 0) file.setLastModified(entry.getTime());
            }
        }
        if (count == 0) throw new IOException("nothing to extract");
    }

    /** Counts the bytes written so far, which is the offset of the next entry in the archive. */
    private static class CountingStream extends java.io.FilterOutputStream {
        long count;

        CountingStream(OutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }
    }

    /**
     * Padding for the extra field of an entry that is stored without compression, so that its
     * data starts at a multiple of {@code alignment}. Android requires this for some files of
     * an apk, such as resources.arsc.
     */
    private static byte[] alignment(long headerOffset, int nameLength, int alignment) {
        long dataOffset = headerOffset + 30 + nameLength;
        int padding = (int) ((alignment - dataOffset % alignment) % alignment);
        if (padding == 0) return new byte[0];
        // The padding is a block with a 4 byte header, so it cannot be shorter than that
        while (padding < 4) padding += alignment;
        byte[] extra = new byte[padding];
        extra[0] = (byte) 0x35;
        extra[1] = (byte) 0xD9;
        extra[2] = (byte) ((padding - 4) & 0xFF);
        extra[3] = (byte) (((padding - 4) >> 8) & 0xFF);
        return extra;
    }

    /**
     * Writes the archive again with the content of one entry replaced by {@code content}.
     * Entries keep their order, and entries that were stored without compression stay so.
     */
    private static void zipReplace(File archive, String name, File content) throws IOException {
        name = trimSlashes(name);
        File temp = new File(archive.getPath() + ".tmp");
        boolean found = false;
        boolean done = false;
        try (ZipFile zip = new ZipFile(archive)) {
            CountingStream counter = new CountingStream(new BufferedOutputStream(new FileOutputStream(temp), BUFFER));
            try (ZipOutputStream to = new ZipOutputStream(counter)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry old = entries.nextElement();
                    boolean replace = trimSlashes(old.getName()).equals(name) && !old.isDirectory();
                    ZipEntry entry = new ZipEntry(old.getName());
                    if (old.getTime() > 0) entry.setTime(replace ? System.currentTimeMillis() : old.getTime());
                    if (old.getMethod() == ZipEntry.STORED) {
                        long size = replace ? content.length() : old.getSize();
                        long crc = old.getCrc();
                        if (replace) {
                            java.util.zip.CRC32 sum = new java.util.zip.CRC32();
                            try (InputStream in = new FileInputStream(content)) {
                                byte[] buffer = new byte[BUFFER];
                                int n;
                                while ((n = in.read(buffer)) > 0) sum.update(buffer, 0, n);
                            }
                            crc = sum.getValue();
                        }
                        entry.setMethod(ZipEntry.STORED);
                        entry.setSize(size);
                        entry.setCompressedSize(size);
                        entry.setCrc(crc);
                        to.flush();
                        int nameLength = old.getName().getBytes(StandardCharsets.UTF_8).length;
                        entry.setExtra(alignment(counter.count, nameLength, old.getName().endsWith(".so") ? 16384 : 4));
                    }
                    to.putNextEntry(entry);
                    if (!old.isDirectory()) {
                        try (InputStream in = replace ? new FileInputStream(content) : zip.getInputStream(old)) {
                            copy(in, to, -1);
                        }
                    }
                    to.closeEntry();
                    if (replace) found = true;
                }
            }
            if (!found) throw new IOException("entry not found: " + name);
            done = true;
        } finally {
            if (!done) temp.delete();
        }
        if (!temp.renameTo(archive)) {
            temp.delete();
            throw new IOException("cannot replace " + archive.getName());
        }
    }

    // ---- ISO 9660, with Joliet names when the image has them

    private static class IsoEntry {
        String path;
        boolean dir;
        long offset;
        long size;
        long time;
    }

    private static int le32(byte[] b, int at) {
        return (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8 | (b[at + 2] & 0xFF) << 16 | (b[at + 3] & 0xFF) << 24;
    }

    private static List<IsoEntry> isoRead(File image) throws IOException {
        List<IsoEntry> result = new ArrayList<>();
        try (RandomAccessFile file = new RandomAccessFile(image, "r")) {
            byte[] sector = new byte[2048];
            byte[] primary = null;
            byte[] joliet = null;
            for (int i = 16; i < 64; i++) {
                if ((long) (i + 1) * 2048 > file.length()) break;
                file.seek((long) i * 2048);
                file.readFully(sector);
                if (sector[1] != 'C' || sector[2] != 'D' || sector[3] != '0' || sector[4] != '0' || sector[5] != '1') break;
                int type = sector[0] & 0xFF;
                if (type == 255) break;
                if (type == 1 && primary == null) {
                    primary = sector.clone();
                } else if (type == 2 && joliet == null && sector[88] == 0x25 && sector[89] == 0x2F
                        && (sector[90] == 0x40 || sector[90] == 0x43 || sector[90] == 0x45)) {
                    joliet = sector.clone();
                }
            }
            if (primary == null) throw new IOException("not an ISO 9660 image");
            byte[] volume = joliet != null ? joliet : primary;
            int blockSize = (volume[128] & 0xFF) | (volume[129] & 0xFF) << 8;
            if (blockSize <= 0) blockSize = 2048;
            long rootBlock = le32(volume, 156 + 2) & 0xFFFFFFFFL;
            long rootSize = le32(volume, 156 + 10) & 0xFFFFFFFFL;
            isoWalk(file, blockSize, rootBlock, rootSize, "", joliet != null, result, new HashSet<Long>(), 0);
        }
        return result;
    }

    private static void isoWalk(RandomAccessFile file, int blockSize, long block, long size, String parent,
                                boolean joliet, List<IsoEntry> result, Set<Long> seen, int depth) throws IOException {
        if (depth > 32 || !seen.add(block) || size <= 0 || size > 64L * 1024 * 1024) return;
        byte[] data = new byte[(int) size];
        file.seek(block * blockSize);
        file.readFully(data);

        IsoEntry open = null;
        int pos = 0;
        while (pos < data.length) {
            int length = data[pos] & 0xFF;
            if (length == 0) {
                // Records do not cross a block, the rest of this block is padding
                pos = (pos / blockSize + 1) * blockSize;
                continue;
            }
            if (length < 34 || pos + length > data.length) break;
            long extent = le32(data, pos + 2) & 0xFFFFFFFFL;
            long dataLength = le32(data, pos + 10) & 0xFFFFFFFFL;
            int flags = data[pos + 25] & 0xFF;
            int nameLength = data[pos + 32] & 0xFF;
            boolean special = nameLength == 1 && (data[pos + 33] == 0 || data[pos + 33] == 1);
            if (!special && pos + 33 + nameLength <= data.length) {
                String name = joliet
                        ? new String(data, pos + 33, nameLength, StandardCharsets.UTF_16BE)
                        : new String(data, pos + 33, nameLength, StandardCharsets.ISO_8859_1);
                int version = name.lastIndexOf(';');
                if (version > 0) name = name.substring(0, version);
                if (name.endsWith(".") && name.length() > 1) name = name.substring(0, name.length() - 1);

                if (open != null) {
                    // The next part of a file that is larger than one record can describe
                    open.size += dataLength;
                    if ((flags & 0x80) == 0) open = null;
                } else {
                    IsoEntry entry = new IsoEntry();
                    entry.path = parent.isEmpty() ? name : parent + "/" + name;
                    entry.dir = (flags & 0x02) != 0;
                    entry.offset = extent * blockSize;
                    entry.size = entry.dir ? 0 : dataLength;
                    entry.time = isoTime(data, pos + 18);
                    result.add(entry);
                    if (entry.dir) {
                        isoWalk(file, blockSize, extent, dataLength, entry.path, joliet, result, seen, depth + 1);
                    } else if ((flags & 0x80) != 0) {
                        open = entry;
                    }
                }
            }
            pos += length;
        }
    }

    private static long isoTime(byte[] b, int at) {
        try {
            Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT);
            calendar.clear();
            calendar.set(1900 + (b[at] & 0xFF), (b[at + 1] & 0xFF) - 1, b[at + 2] & 0xFF,
                    b[at + 3] & 0xFF, b[at + 4] & 0xFF, b[at + 5] & 0xFF);
            // The offset from UTC is counted in quarters of an hour
            return Math.max(0, calendar.getTimeInMillis() / 1000 - b[at + 6] * 15L * 60);
        } catch (Throwable e) {
            return 0;
        }
    }

    private static void isoExtract(File image, File dest, String prefix) throws IOException {
        prefix = trimSlashes(prefix);
        List<IsoEntry> entries = isoRead(image);
        int count = 0;
        try (RandomAccessFile file = new RandomAccessFile(image, "r")) {
            byte[] buffer = new byte[BUFFER];
            for (IsoEntry entry : entries) {
                String relative = relative(entry.path, prefix);
                if (relative == null) continue;
                File target = target(dest, relative);
                count++;
                if (entry.dir) {
                    target.mkdirs();
                    continue;
                }
                File parent = target.getParentFile();
                if (parent != null) parent.mkdirs();
                try (OutputStream to = new FileOutputStream(target)) {
                    file.seek(entry.offset);
                    long left = entry.size;
                    while (left > 0) {
                        int n = file.read(buffer, 0, (int) Math.min(buffer.length, left));
                        if (n < 0) throw new IOException("unexpected end of image");
                        to.write(buffer, 0, n);
                        left -= n;
                    }
                }
                if (entry.time > 0) target.setLastModified(entry.time * 1000);
            }
        }
        if (count == 0) throw new IOException("nothing to extract");
    }

    // ---- split and join

    private static String partName(String base, int index) {
        return base + "." + String.format(Locale.ROOT, "%03d", index);
    }

    private static void split(File file, long partSize) throws IOException {
        if (!file.isFile()) throw new IOException("not a file: " + file);
        if (partSize <= 0) throw new IOException("bad part size");
        long length = file.length();
        if (length <= partSize) throw new IOException("the file is not larger than one part");
        long parts = (length + partSize - 1) / partSize;
        if (parts > 999) throw new IOException("too many parts, choose a larger part size");
        for (int i = 1; i <= parts; i++) {
            if (new File(partName(file.getPath(), i)).exists()) {
                throw new IOException(partName(file.getName(), i) + " already exists");
            }
        }
        try (InputStream in = new FileInputStream(file)) {
            long left = length;
            for (int i = 1; left > 0; i++) {
                long count = Math.min(partSize, left);
                try (OutputStream to = new FileOutputStream(partName(file.getPath(), i))) {
                    copy(in, to, count);
                }
                left -= count;
            }
        }
        out.println(parts);
    }

    private static void join(File part) throws IOException {
        String path = part.getPath();
        int dot = path.lastIndexOf('.');
        if (dot < 0 || !path.substring(dot + 1).matches("\\d{3}")) {
            throw new IOException("choose a part like name.001");
        }
        String base = path.substring(0, dot);
        File output = new File(base);
        if (output.exists()) throw new IOException(output.getName() + " already exists");
        if (!new File(partName(base, 1)).isFile()) throw new IOException("the first part (.001) is missing");
        boolean done = false;
        int count = 0;
        try (OutputStream to = new FileOutputStream(output)) {
            for (int i = 1; i <= 999; i++) {
                File next = new File(partName(base, i));
                if (!next.isFile()) break;
                try (InputStream in = new FileInputStream(next)) {
                    copy(in, to, -1);
                }
                count++;
            }
            done = true;
        } finally {
            if (!done) output.delete();
        }
        out.println(count);
    }
}
