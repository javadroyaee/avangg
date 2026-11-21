package ir.jrweb.avangvpn

data class VpnConfig(
    val id: String,
    val name: String,
    val protocol: String,
    val host: String,
    val port: Int,
    val rawUri: String,
    var pingMs: Int = -1,
    var isSelected: Boolean = false
)

data class SubscriptionInfo(
    var url: String = "",
    var title: String = "گروه اشتراک آونگ",
    var usedBytes: Long = 0L,
    var totalBytes: Long = 0L,
    var expireTimestamp: Long = 0L
)