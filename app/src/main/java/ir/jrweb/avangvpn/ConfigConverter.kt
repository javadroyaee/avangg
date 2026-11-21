package ir.jrweb.avangvpn

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

object ConfigConverter {

    fun buildXrayConfig(config: VpnConfig): String {
        val outbound = when (config.protocol) {
            "VLESS" -> parseVlessOutbound(config.rawUri)
            "VMESS" -> parseVmessOutbound(config.rawUri)
            "TROJAN" -> parseTrojanOutbound(config.rawUri)
            "SS" -> parseShadowsocksOutbound(config.rawUri)
            "WIREGUARD" -> parseWireGuardOutbound(config.rawUri)
            else -> parseVlessOutbound(config.rawUri)
        }

        val root = JSONObject().apply {
            put("log", JSONObject().put("loglevel", "warning"))
            put("dns", JSONObject().put("servers", JSONArray().put("1.1.1.1").put("8.8.8.8")))
            put("inbounds", JSONArray().apply {
                put(JSONObject().apply {
                    put("tag", "socks")
                    put("port", 10808)
                    put("listen", "127.0.0.1")
                    put("protocol", "socks")
                    put("settings", JSONObject().put("udp", true))
                })
            })
            put("outbounds", JSONArray().apply {
                put(outbound)
                put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
            })
        }
        return root.toString(2)
    }

    private fun parseQuery(query: String?): Map<String, String> {
        val map = mutableMapOf<String, String>()
        if (query.isNullOrEmpty()) return map
        for (pair in query.split("&")) {
            val idx = pair.indexOf("=")
            if (idx > 0) {
                val k = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                val v = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                map[k] = v
            }
        }
        return map
    }

    private fun parseVlessOutbound(uriStr: String): JSONObject {
        val uri = URI(uriStr)
        val params = parseQuery(uri.rawQuery)
        val userObj = JSONObject().apply {
            put("id", uri.userInfo ?: "")
            put("encryption", params["encryption"] ?: "none")
            val flow = params["flow"]
            if (!flow.isNullOrEmpty()) put("flow", flow)
        }
        val vnextObj = JSONObject().apply {
            put("address", uri.host ?: "")
            put("port", if (uri.port > 0) uri.port else 443)
            put("users", JSONArray().put(userObj))
        }
        return JSONObject().apply {
            put("tag", "proxy")
            put("protocol", "vless")
            put("settings", JSONObject().put("vnext", JSONArray().put(vnextObj)))
            put("streamSettings", buildStreamSettings(params, uri.host ?: ""))
        }
    }

    private fun parseTrojanOutbound(uriStr: String): JSONObject {
        val uri = URI(uriStr)
        val params = parseQuery(uri.rawQuery)
        val serverObj = JSONObject().apply {
            put("address", uri.host ?: "")
            put("port", if (uri.port > 0) uri.port else 443)
            put("password", uri.userInfo ?: "")
        }
        return JSONObject().apply {
            put("tag", "proxy")
            put("protocol", "trojan")
            put("settings", JSONObject().put("servers", JSONArray().put(serverObj)))
            put("streamSettings", buildStreamSettings(params, uri.host ?: ""))
        }
    }

    private fun parseVmessOutbound(uriStr: String): JSONObject {
        val b64 = uriStr.removePrefix("vmess://")
        val json = JSONObject(String(Base64.decode(b64, Base64.DEFAULT)))
        val userObj = JSONObject().apply {
            put("id", json.optString("id"))
            put("alterId", json.optInt("aid", 0))
            put("security", json.optString("scy", "auto"))
        }
        val vnextObj = JSONObject().apply {
            put("address", json.optString("add"))
            put("port", json.optInt("port", 443))
            put("users", JSONArray().put(userObj))
        }
        val params = mapOf(
            "type" to json.optString("net", "tcp"),
            "host" to json.optString("host", ""),
            "path" to json.optString("path", "/"),
            "security" to json.optString("tls", ""),
            "sni" to json.optString("sni", json.optString("host", ""))
        )
        return JSONObject().apply {
            put("tag", "proxy")
            put("protocol", "vmess")
            put("settings", JSONObject().put("vnext", JSONArray().put(vnextObj)))
            put("streamSettings", buildStreamSettings(params, json.optString("add")))
        }
    }

    private fun parseShadowsocksOutbound(uriStr: String): JSONObject {
        val uri = URI(uriStr)
        val decoded = String(Base64.decode(uri.userInfo ?: "", Base64.DEFAULT))
        val method = decoded.substringBefore(":", "aes-256-gcm")
        val pass = decoded.substringAfter(":", "")
        val serverObj = JSONObject().apply {
            put("address", uri.host ?: "")
            put("port", if (uri.port > 0) uri.port else 8388)
            put("method", method)
            put("password", pass)
        }
        return JSONObject().apply {
            put("tag", "proxy")
            put("protocol", "shadowsocks")
            put("settings", JSONObject().put("servers", JSONArray().put(serverObj)))
        }
    }

    private fun parseWireGuardOutbound(raw: String): JSONObject {
        var privKey = ""
        var localAddr = "10.0.0.2/32"
        var pubKey = ""
        var endpoint = ""
        if (raw.contains("[Interface]")) {
            for (line in raw.lines()) {
                val t = line.trim()
                when {
                    t.startsWith("PrivateKey", true) -> privKey = t.substringAfter("=").trim()
                    t.startsWith("Address", true) -> localAddr = t.substringAfter("=").trim().substringBefore(",")
                    t.startsWith("PublicKey", true) -> pubKey = t.substringAfter("=").trim()
                    t.startsWith("Endpoint", true) -> endpoint = t.substringAfter("=").trim()
                }
            }
        } else {
            val normalized = raw.replace("wg://", "wireguard://")
            val uri = URI(normalized)
            val params = parseQuery(uri.rawQuery)
            privKey = uri.userInfo ?: ""
            localAddr = params["address"] ?: "10.0.0.2/32"
            pubKey = params["publickey"] ?: ""
            endpoint = "${uri.host}:${if (uri.port > 0) uri.port else 51820}"
        }
        val peerObj = JSONObject().apply {
            put("publicKey", pubKey)
            put("endpoint", endpoint)
        }
        return JSONObject().apply {
            put("tag", "proxy")
            put("protocol", "wireguard")
            put("settings", JSONObject().apply {
                put("secretKey", privKey)
                put("address", JSONArray().put(localAddr))
                put("peers", JSONArray().put(peerObj))
            })
        }
    }

    private fun buildStreamSettings(params: Map<String, String>, defaultHost: String): JSONObject {
        val network = params["type"] ?: "tcp"
        val security = params["security"] ?: "none"
        val stream = JSONObject().apply {
            put("network", network)
            put("security", if (security.isEmpty()) "none" else security)
        }

        if (network == "ws") {
            val wsObj = JSONObject().put("path", params["path"] ?: "/")
            val host = params["host"]
            if (!host.isNullOrEmpty()) {
                wsObj.put("headers", JSONObject().put("Host", host))
            }
            stream.put("wsSettings", wsObj)
        } else if (network == "grpc") {
            stream.put("grpcSettings", JSONObject().put("serviceName", params["serviceName"] ?: ""))
        }

        if (security == "tls") {
            stream.put("tlsSettings", JSONObject().apply {
                put("serverName", params["sni"] ?: params["host"] ?: defaultHost)
                put("allowInsecure", true)
            })
        } else if (security == "reality") {
            stream.put("realitySettings", JSONObject().apply {
                put("serverName", params["sni"] ?: defaultHost)
                put("fingerprint", params["fp"] ?: "chrome")
                put("publicKey", params["pbk"] ?: "")
                put("shortId", params["sid"] ?: "")
            })
        }
        return stream
    }
}