package com.lisb.android.mediashrink

class TooMovieLongException : Exception {
    constructor(detail: String?) : super(detail)
    constructor(detail: String?, cause: Exception?) : super(detail, cause)

    companion object {
        private const val serialVersionUID = 2936261485262150711L
    }
}