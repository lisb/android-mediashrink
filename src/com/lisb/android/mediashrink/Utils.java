package com.lisb.android.mediashrink;

import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaCodecInfo.CodecCapabilities;
import android.media.MediaCodecInfo.CodecProfileLevel;
import android.util.Log;

public class Utils {

	private static final String LOG_TAG = Utils.class.getSimpleName();

	public static void printCodecCapabilities() {
		Log.v(LOG_TAG, "print codec capablities.");
		for (int i = 0, size = MediaCodecList.getCodecCount(); i < size; i++) {
			final MediaCodecInfo info = MediaCodecList.getCodecInfoAt(i);
			if (info.isEncoder()) {
				Log.v(LOG_TAG, "  MediaCodecInfo:" + info.getName());
				for (final String type : info.getSupportedTypes()) {
					final CodecCapabilities capabilities = info
							.getCapabilitiesForType(type);
					Log.v(LOG_TAG, "    type:" + type);
					Log.v(LOG_TAG,
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

					Log.v(LOG_TAG, "    profile:" + sb.toString());
				}
			}
		}
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

}
