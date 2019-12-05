package com.lisb.android.mediashrink

class DecoderCreationException : Exception {

    constructor(message: String?) : super(message)
    constructor(detailMessage: String?, throwable: Throwable?) : super(detailMessage, throwable)

    companion object {
        private const val serialVersionUID = -1447299098199262989L
    }
}