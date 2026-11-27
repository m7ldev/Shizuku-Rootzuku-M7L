package moe.shizuku.manager.log

import android.os.Parcel
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants
import rikka.shizuku.server.ServerConstants

/**
 * Reads the activity log kept by the Shizuku service.
 */
object ActivityLogManager {

    const val TYPE_BINDER = 0
    const val TYPE_SHELL = 1
    const val TYPE_USER_SERVICE = 2

    class Entry(
        val time: Long,
        val uid: Int,
        val packageName: String?,
        val type: Int,
        val detail: String?,
        val denied: Boolean,
        val count: Int
    )

    /**
     * @return entries, newest first, or null if the running service is too old to have a log
     */
    fun get(): List<Entry>? {
        val binder = Shizuku.getBinder() ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR)
            if (!binder.transact(ServerConstants.BINDER_TRANSACTION_getActivityLog, data, reply, 0)) {
                return null
            }
            reply.readException()
            val size = reply.readInt()
            val list = ArrayList<Entry>(size)
            for (i in 0 until size) {
                val time = reply.readLong()
                val uid = reply.readInt()
                val packageName = reply.readString()
                val type = reply.readInt()
                val detail = reply.readString()
                val denied = reply.readInt() != 0
                val count = reply.readInt()
                list.add(Entry(time, uid, packageName, type, detail, denied, count))
            }
            list
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    fun clear() {
        val binder = Shizuku.getBinder() ?: return
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR)
            if (binder.transact(ServerConstants.BINDER_TRANSACTION_clearActivityLog, data, reply, 0)) {
                reply.readException()
            }
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
