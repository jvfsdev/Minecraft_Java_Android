package dev.prismdroid.auth

import dev.prismdroid.BuildConfig
import dev.prismdroid.net.Http
import dev.prismdroid.net.HttpException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import java.io.IOException
import java.util.UUID

/**
 * Microsoft account → Xbox Live → XSTS → Minecraft services, the same chain Prism Launcher
 * uses (endpoints confirmed in the Prism binary). Uses the OAuth 2.0 device-code flow, so the
 * user signs in on Microsoft's own page and this app never sees the password.
 */
object MicrosoftAuth {
    private const val MSA = "https://login.microsoftonline.com/consumers/oauth2/v2.0"
    private const val SCOPE = "XboxLive.signin offline_access"

    val clientId: String get() = BuildConfig.MSA_CLIENT_ID

    data class DeviceCode(
        val deviceCode: String, val userCode: String, val verificationUri: String,
        val expiresIn: Int, val interval: Int, val message: String,
    )

    class AuthError(message: String) : IOException(message)

    private fun JsonElement.str(key: String) = jsonObject[key]?.jsonPrimitive?.content
        ?: throw AuthError("missing '$key' in response")

    suspend fun requestDeviceCode(): DeviceCode {
        require(clientId.isNotBlank()) {
            "MSA client ID not configured. Register an Azure app (public client, personal accounts) " +
                "approved for Minecraft and set msaClientId in gradle.properties."
        }
        val r = Http.postForm("$MSA/devicecode", mapOf("client_id" to clientId, "scope" to SCOPE))
        return DeviceCode(
            r.str("device_code"), r.str("user_code"), r.str("verification_uri"),
            r.str("expires_in").toInt(), r.jsonObject["interval"]?.jsonPrimitive?.int ?: 5, r.str("message"),
        )
    }

    data class MsaTokens(val accessToken: String, val refreshToken: String, val expiresAt: Long)

    private fun parseMsa(r: JsonElement) = MsaTokens(
        r.str("access_token"), r.str("refresh_token"),
        System.currentTimeMillis() + r.str("expires_in").toLong() * 1000,
    )

    /** Polls the token endpoint until the user finishes signing in. */
    suspend fun pollDeviceCode(dc: DeviceCode): MsaTokens {
        var interval = dc.interval.toLong()
        val deadline = System.currentTimeMillis() + dc.expiresIn * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(interval * 1000)
            try {
                return parseMsa(Http.postForm("$MSA/token", mapOf(
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                    "client_id" to clientId, "device_code" to dc.deviceCode,
                )))
            } catch (e: HttpException) {
                val err = runCatching { Http.json.parseToJsonElement(e.body).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
                when (err) {
                    "authorization_pending" -> {}
                    "slow_down" -> interval += 5
                    "authorization_declined" -> throw AuthError("Sign-in was declined.")
                    "expired_token" -> throw AuthError("The code expired. Try again.")
                    else -> throw e
                }
            }
        }
        throw AuthError("The code expired. Try again.")
    }

    suspend fun refreshMsa(refreshToken: String): MsaTokens = parseMsa(Http.postForm("$MSA/token", mapOf(
        "grant_type" to "refresh_token", "client_id" to clientId,
        "refresh_token" to refreshToken, "scope" to SCOPE,
    )))

    /** Full chain from a Microsoft access token to a ready-to-launch Minecraft account. */
    suspend fun loginMinecraft(msa: MsaTokens): Account {
        // Xbox Live user token
        val xbl = Http.postJson("https://user.auth.xboxlive.com/user/authenticate", buildJsonObject {
            putJsonObject("Properties") {
                put("AuthMethod", "RPS"); put("SiteName", "user.auth.xboxlive.com")
                put("RpsTicket", "d=${msa.accessToken}")
            }
            put("RelyingParty", "http://auth.xboxlive.com"); put("TokenType", "JWT")
        })
        val xblToken = xbl.str("Token")

        // XSTS token for Minecraft services
        val xsts = try {
            Http.postJson("https://xsts.auth.xboxlive.com/xsts/authorize", buildJsonObject {
                putJsonObject("Properties") {
                    put("SandboxId", "RETAIL"); putJsonArray("UserTokens") { add(xblToken) }
                }
                put("RelyingParty", "rp://api.minecraftservices.com/"); put("TokenType", "JWT")
            })
        } catch (e: HttpException) {
            val xerr = runCatching { Http.json.parseToJsonElement(e.body).jsonObject["XErr"]?.jsonPrimitive?.long }.getOrNull()
            throw AuthError(when (xerr) {
                2148916233 -> "This Microsoft account has no Xbox profile. Sign in once at minecraft.net to create one."
                2148916235 -> "Xbox Live is not available in your country."
                2148916236, 2148916237 -> "This account needs adult verification on xbox.com."
                2148916238 -> "Child account: an adult must add it to a Microsoft family."
                else -> "Xbox authorization failed (${e.code})."
            })
        }
        val xstsToken = xsts.str("Token")
        val uhs = xsts.jsonObject["DisplayClaims"]!!.jsonObject["xui"]!!.jsonArray[0].jsonObject["uhs"]!!.jsonPrimitive.content

        // Minecraft access token
        val mc = try {
            Http.postJson("https://api.minecraftservices.com/launcher/login", buildJsonObject {
                put("xtoken", "XBL3.0 x=$uhs;$xstsToken"); put("platform", "PC_LAUNCHER")
            })
        } catch (e: HttpException) {
            // Microsoft and Xbox accepted the account; Mojang rejects client IDs it has not allow-listed.
            if (e.code == 403) throw AuthError(
                "Microsoft sign-in worked, but Mojang has not approved this app's client ID yet " +
                    "(api.minecraftservices.com returned 403). Wait for the approval from aka.ms/mce-reviewappid.")
            throw e
        }
        val mcToken = mc.str("access_token")
        val mcExpires = System.currentTimeMillis() + mc.str("expires_in").toLong() * 1000
        val bearer = mapOf("Authorization" to "Bearer $mcToken")

        // Ownership (entitlements) and profile
        val ent = Http.getJson("https://api.minecraftservices.com/entitlements/license?requestId=${UUID.randomUUID()}", bearer)
        val owns = ent.jsonObject["items"]?.jsonArray?.any {
            val n = it.jsonObject["name"]?.jsonPrimitive?.content
            n == "product_minecraft" || n == "game_minecraft"
        } == true
        if (!owns) throw AuthError("This account does not own Minecraft: Java Edition.")

        val profile = try {
            Http.getJson("https://api.minecraftservices.com/minecraft/profile", bearer)
        } catch (e: HttpException) {
            if (e.code == 404) throw AuthError("No Minecraft profile yet. Pick a username at minecraft.net first.")
            throw e
        }
        return Account(
            uuid = profile.str("id"), name = profile.str("name"),
            mcAccessToken = mcToken, mcExpiresAt = mcExpires,
            msaRefreshToken = msa.refreshToken, xuid = uhs,
        )
    }

    /** Returns a valid account, refreshing the Minecraft token if it is about to expire. */
    suspend fun ensureFresh(acc: Account): Account {
        if (acc.mcExpiresAt - System.currentTimeMillis() > 10 * 60_000) return acc
        return loginMinecraft(refreshMsa(acc.msaRefreshToken))
    }
}
