package moe.shizuku.manager.tools

import android.os.ParcelFileDescriptor
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.concurrent.thread

/**
 * Runs shell commands through the Shizuku service, with the identity of the service
 * (adb shell or root). All functions block, call them from a background thread.
 */
object ShizukuShell {

    class Result(val code: Int, val out: String, val err: String) {
        val isSuccess: Boolean get() = code == 0

        /** Output for humans: what the command printed, on stdout or stderr. */
        val message: String
            get() {
                val text = (out.trim() + "\n" + err.trim()).trim()
                return if (text.isEmpty()) "exit code $code" else text
            }
    }

    /**
     * Quotes a value so that the shell passes it to a command unchanged.
     */
    fun quote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }

    private fun readAll(input: InputStream, limit: Int): String {
        val buffer = ByteArray(8192)
        val out = ByteArrayOutputStream()
        try {
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                // Keep reading after the limit so that the process is not blocked on a full pipe
                if (out.size() < limit) out.write(buffer, 0, minOf(n, limit - out.size()))
            }
        } catch (e: Throwable) {
        } finally {
            try {
                input.close()
            } catch (e: Throwable) {
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /**
     * @param command a line for "sh -c"
     * @param limit maximum number of bytes kept from each of stdout and stderr
     */
    fun exec(command: String, limit: Int = 1024 * 1024): Result {
        val binder = Shizuku.getBinder() ?: return Result(-1, "", "Shizuku is not running")
        return try {
            val service = IShizukuService.Stub.asInterface(binder)
            val process = service.newProcess(arrayOf("sh", "-c", command), null, null)
            try {
                process.outputStream?.close()
            } catch (e: Throwable) {
            }

            var err = ""
            val errThread = thread(name = "shizuku-shell-stderr") {
                err = readAll(ParcelFileDescriptor.AutoCloseInputStream(process.errorStream), limit)
            }
            val out = readAll(ParcelFileDescriptor.AutoCloseInputStream(process.inputStream), limit)
            val code = process.waitFor()
            errThread.join(2000)
            try {
                process.destroy()
            } catch (e: Throwable) {
            }
            Result(code, out, err)
        } catch (e: Throwable) {
            Result(-1, "", e.toString())
        }
    }
}
