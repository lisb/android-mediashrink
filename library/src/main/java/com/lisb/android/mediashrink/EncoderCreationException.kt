package com.lisb.android.mediashrink

class EncoderCreationException : Exception {

    constructor(message: String?) : super(message)
    constructor(detailMessage: String?, throwable: Throwable?) : super(detailMessage, throwable)

    companion object {
        private const val serialVersionUID = -2376268783992794022L
    }
}