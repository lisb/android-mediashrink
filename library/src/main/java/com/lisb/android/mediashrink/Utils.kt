package com.lisb.android.mediashrink

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaFormat
import timber.log.Timber
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

object Utils {
    private const val TAG = "Utils"
    fun printCodecCapabilities(encoder: Boolean) {
        var i = 0
        val size = MediaCodecList.getCodecCount()
        while (i < size) {
            val info = MediaCodecList.getCodecInfoAt(i)
            if (info.isEncoder == encoder) {
                val sb = StringBuilder()
                sb.appendln("MediaCodecInfo: ${info.name}")
                for (type in info.supportedTypes) {
                    val capabilities = info.getCapabilitiesForType(type)
                    sb.appendln("\ttype: $type")
                    sb.appendln("\t\tcolor format: ${capabilities.colorFormats?.contentToString()}")
                    sb.append("\t\tprofile levels: [")
                    for (l in capabilities.profileLevels) {
                        sb.append("{level:${l.level}, profile:${Integer.toHexString(l.profile)}},")
                    }
                    sb.appendln("]")
                }
                Timber.tag(TAG).v(sb.toString())
            }
            i++
        }
    }

    fun toString(profileLevels: Array<CodecProfileLevel>): String {
        val builder = StringBuilder()
        builder.append('[')
        for (profileLevel in profileLevels) {
            if (builder.length > 1) builder.append(", ")
            builder.append("{profile:${profileLevel.profile}, level:${profileLevel.level}}")
        }
        builder.append(']')
        return builder.toString()
    }

    fun toString(format: MediaFormat): String {
        val csdStringBuilder = StringBuilder()
        var csdIndex = 0
        var csdKey = "csd-$csdIndex"
        while (format.containsKey(csdKey)) {
            val buf = format.getByteBuffer(csdKey)
            csdStringBuilder.append(", $csdKey:${buf?.array()?.contentToString()}")
            csdIndex++
            csdKey = "csd-$csdIndex"
        }
        return format.toString() + csdStringBuilder.toString()
    }

    fun closeSilently(c: Closeable?) {
        if (c == null) {
            return
        }
        try {
            c.close()
        } catch (e: IOException) {
        }
    }

    fun closeSilently(codec: MediaCodec?) {
        if (codec == null) {
            return
        }
        codec.stop()
        codec.release()
    }

    /**
     * NOTE: this method does not close streams.
     */
    @Throws(IOException::class)
    fun copy(inStream: InputStream, outStream: OutputStream) {
        val bytes = ByteArray(1024)
        var byteCount: Int
        while (inStream.read(bytes).also { byteCount = it } != -1) {
            outStream.write(bytes, 0, byteCount)
        }
    }

    /**
     * Returns the first codec capable of encoding the specified MIME type, or
     * null if no match was found.
     */
    fun selectCodec(mimeType: String?, encoder: Boolean): MediaCodecInfo? {
        var i = 0
        val numCodecs = MediaCodecList.getCodecCount()
        while (i < numCodecs) {
            val codecInfo = MediaCodecList.getCodecInfoAt(i)
            if (codecInfo.isEncoder != encoder) {
                i++
                continue
            }
            val types = codecInfo.supportedTypes
            for (j in types.indices) {
                if (types[j].equals(mimeType, ignoreCase = true)) {
                    return codecInfo
                }
            }
            i++
        }
        return null
    }
}