package com.lisb.android.mediashrink;

public class TooMovieLongException extends Exception {

	private static final long serialVersionUID = 2936261485262150711L;

	public TooMovieLongException(String detail) {
		super(detail);
	}
	
	public TooMovieLongException(String detail, Exception cause) {
		super(detail, cause);
	}
	
}
