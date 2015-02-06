package com.lisb.android.mediashrink;

import java.io.IOException;

public class ExternalStageNotMounted extends IOException {
	private static final long serialVersionUID = -8024785317915359948L;

	public ExternalStageNotMounted(String detailMessage) {
		super(detailMessage);
	}

}
