package au.josh.unifiphone.core.engine

/** Lightweight diagnostic log shared by the media pipeline; SipEngine points
 * the sink at sip_debug.log so video-path events land next to the SIP trace. */
object EngineLog {
    @Volatile var sink: ((String) -> Unit)? = null
    fun d(msg: String) { runCatching { sink?.invoke(msg) } }
}
