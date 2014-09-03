package com.lisb.android.mediashrink;

public class DecodeException extends Exception {

	private static final long serialVersionUID = 2936261485262150711L;

	public DecodeException(String detail) {
		super(detail);
	}
	
	public DecodeException(String detail, Exception cause) {
		super(detail, cause);
	}
	
}
