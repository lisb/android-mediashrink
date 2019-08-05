package com.lisb.android.mediashrink;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecInfo.CodecCapabilities;
import android.media.MediaCodecInfo.CodecProfileLevel;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.util.Log;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class Utils {

	private static final String TAG = "Utils";

	public static void printCodecCapabilities(boolean encoder) {
		Log.v(TAG, "print codec capablities.");
		for (int i = 0, size = MediaCodecList.getCodecCount(); i < size; i++) {
			final MediaCodecInfo info = MediaCodecList.getCodecInfoAt(i);
			if (info.isEncoder() == encoder) {
				Log.v(TAG, "  MediaCodecInfo:" + info.getName());
				for (final String type : info.getSupportedTypes()) {
					final CodecCapabilities capabilities = info
							.getCapabilitiesForType(type);
					Log.v(TAG, "    type:" + type);
					Log.v(TAG,
							"    color format:"
									+ Arrays.toString(capabilities.colorFormats));

					StringBuilder sb = new StringBuilder();
					sb.append("    profile levels:");
					sb.append('[');
					for (final CodecProfileLevel l : capabilities.profileLevels) {
						sb.append('{');
						sb.append("level:");
						sb.append(Integer.toString(l.level));
						sb.append(", profile:");
						sb.append(Integer.toHexString(l.profile));
						sb.append('}');
						sb.append(',');
					}
					sb.append(']');

					Log.v(TAG, "    profile:" + sb.toString());
				}
			}
		}
	}

	public static String toString(final CodecProfileLevel[] profileLevels) {
		final StringBuilder builder = new StringBuilder();
		builder.append('[');
		for (final CodecProfileLevel profileLevel : profileLevels) {
			if (builder.length() > 1) {
				builder.append(", ");
			}
			builder.append('{');
			builder.append("profile:");
			builder.append(profileLevel.profile);
			builder.append(",level:");
			builder.append(profileLevel.level);
			builder.append('}');
		}
		builder.append(']');
		return builder.toString();
	}

	public static String toString(final MediaFormat format) {
		final StringBuilder csdStringBuilder = new StringBuilder();
		int csdIndex = 0;
		String csdKey = "csd-" + csdIndex;
		while (format.containsKey(csdKey)) {
			final ByteBuffer buf = format.getByteBuffer(csdKey);
			csdStringBuilder.append(", ");
			csdStringBuilder.append(csdKey);
			csdStringBuilder.append(':');
			csdStringBuilder.append(Arrays.toString(buf.array()));
			csdIndex++;
			csdKey = "csd-" + csdIndex;
		}
		return format.toString() + csdStringBuilder.toString();
	}

	public static void closeSilently(final Closeable c) {
		if (c == null) {
			return;
		}

		try {
			c.close();
		} catch (IOException e) {
		}
	}

	public static void closeSilently(final MediaCodec codec) {
		if (codec == null) {
			return;
		}

		codec.stop();
		codec.release();
	}

	/**
	 * NOTE: this method does not close streams.
     */
	public static void copy(final InputStream in, final OutputStream out) throws IOException {
		final byte[] bytes = new byte[1024];
		int byteCount;
		while ((byteCount = in.read(bytes)) != -1) {
			out.write(bytes, 0, byteCount);
		}
	}

	/**
	 * Returns the first codec capable of encoding the specified MIME type, or
	 * null if no match was found.
	 */
	public static MediaCodecInfo selectCodec(String mimeType, boolean encoder) {
		for (int i = 0, numCodecs = MediaCodecList.getCodecCount(); i < numCodecs; i++) {
			final MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);

			if (codecInfo.isEncoder() != encoder) {
				continue;
			}

			final String[] types = codecInfo.getSupportedTypes();
			for (int j = 0; j < types.length; j++) {
				if (types[j].equalsIgnoreCase(mimeType)) {
					return codecInfo;
				}
			}
		}
		return null;
	}
}
