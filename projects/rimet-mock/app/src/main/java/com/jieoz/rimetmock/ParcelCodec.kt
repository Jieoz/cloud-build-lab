package com.jieoz.rimetmock

import android.os.Parcel
import android.os.Parcelable
import android.util.Base64

/**
 * Marshal any Parcelable to a Base64 string and back.
 *
 * This is how the original module carried a *consistent* radio-environment snapshot: real
 * ScanResult / WifiInfo / CellInfo objects have no public constructors, so you cannot rebuild
 * them field-by-field. But they are all Parcelable, so `Parcel.marshall()` captures the genuine
 * object and the class CREATOR reconstructs a genuine object of the same type on replay.
 *
 * Unlike the original (which marshalled a whole Profile and broke across Android versions), we
 * only ever marshal short-lived framework objects that are unmarshalled on the SAME device build
 * that captured them, so the marshalled-Parcel version fragility does not apply.
 */
object ParcelCodec {

    fun <T : Parcelable> encode(value: T?): String? {
        if (value == null) return null
        val p = Parcel.obtain()
        return try {
            value.writeToParcel(p, 0)
            Base64.encodeToString(p.marshall(), Base64.NO_WRAP)
        } finally {
            p.recycle()
        }
    }

    fun <T> decode(b64: String?, creator: Parcelable.Creator<T>): T? {
        if (b64.isNullOrEmpty()) return null
        val bytes = runCatching { Base64.decode(b64, Base64.NO_WRAP) }.getOrNull() ?: return null
        val p = Parcel.obtain()
        return try {
            p.unmarshall(bytes, 0, bytes.size)
            p.setDataPosition(0)
            creator.createFromParcel(p)
        } catch (_: Throwable) {
            null
        } finally {
            p.recycle()
        }
    }

    fun <T> decodeList(b64s: List<String>, creator: Parcelable.Creator<T>): List<T> =
        b64s.mapNotNull { decode(it, creator) }

    /**
     * Same as [decode] but resolves the class's CREATOR reflectively. Needed for framework types
     * whose CREATOR field is hidden from the public SDK (e.g. WifiInfo.CREATOR), so it cannot be
     * referenced by name at compile time.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> decodeReflect(b64: String?, cls: Class<T>): T? {
        val creator = creatorOf(cls) ?: return null
        return decode(b64, creator) as T?
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> decodeListReflect(b64s: List<String>, cls: Class<T>): List<T> {
        val creator = creatorOf(cls) ?: return emptyList()
        return b64s.mapNotNull { decode(it, creator) as T? }
    }

    private fun creatorOf(cls: Class<*>): Parcelable.Creator<*>? = runCatching {
        cls.getField("CREATOR").get(null) as? Parcelable.Creator<*>
    }.getOrNull()
}
