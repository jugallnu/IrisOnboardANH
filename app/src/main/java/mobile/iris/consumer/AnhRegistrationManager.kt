package mobile.iris.consumer

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

class DeviceRegistrationManager(private val context: Context) {

    companion object {
        private const val TAG = "DeviceRegistrationManager"
        private const val PREFS_NAME = "device_prefs"
        private const val KEY_INSTALLATION_ID = "installation_id"
        private const val PLATFORM = "FcmV1"   // Android FCM — iOS would use "Apns"

        // Replace with your deployed Azure Function URL and key
        private const val ENDPOINT_URL = "https://irismobilebackendfhl-c5dqd6eaffbvbvhr.canadacentral-01.azurewebsites.net/api/register"
        private const val FUNCTION_KEY  = "FunctionKey"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Returns a stable installation ID for this device.
     * Generated once and persisted in SharedPreferences.
     */
    private fun getInstallationId(): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_INSTALLATION_ID, null)
            ?: UUID.randomUUID().toString().also { newId ->
                prefs.edit().putString(KEY_INSTALLATION_ID, newId).apply()
                Log.d(TAG, "Generated new installationId: $newId")
            }
    }

    /**
     * Converts an MSA OID (format 00000000-0000-0000-HHHH-HHHHHHHHHHHH) to PUIDINT.
     * The PUID is encoded in the last two GUID groups as a 64-bit unsigned integer.
     * Example: 00000000-0000-0000-0d6d-bdf8171451fd → 959728586995171837
     */
    private fun oidToPuidInt(oid: String): String? {
        return try {
            val parts = oid.split("-")
            if (parts.size != 5) return null
            val hexPuid = parts[3] + parts[4]   // 4 + 12 hex chars = 8 bytes
            java.lang.Long.toUnsignedString(java.lang.Long.parseUnsignedLong(hexPuid, 16))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert OID to PUIDINT: $oid", e)
            null
            }
    }

    /**
     * Sends FCM token, userId, installationId and locale to the Azure Function.
     * The function acquires the ICP partner token via Managed Identity and
     * calls the ANH consumer V2 registration API.
     */
    suspend fun register(fcmToken: String, userId: String?): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val installationId = getInstallationId()
                val locale = Locale.getDefault().toLanguageTag()
                val puidInt = userId?.let { oidToPuidInt(it) }

                val payload = JSONObject().apply {
                    put("fcmToken",       fcmToken)
                    put("userId",         puidInt)
                    put("installationId", installationId)
                    put("platform",       PLATFORM)
                    put("locale",         locale)
                }

                Log.i(TAG, "══════════════════════════════════════════")
                Log.i(TAG, "REGISTRATION PAYLOAD")
                Log.i(TAG, "  endpoint      : $ENDPOINT_URL")
                Log.i(TAG, "  fcmToken      : $fcmToken")
                Log.i(TAG, "  userId (oid)  : $userId")
                Log.i(TAG, "  userId (puid) : $puidInt")
                Log.i(TAG, "  installationId: $installationId")
                Log.i(TAG, "  platform      : $PLATFORM")
                Log.i(TAG, "  locale        : $locale")
                Log.i(TAG, "══════════════════════════════════════════")

                val request = Request.Builder()
                    .url(ENDPOINT_URL)
                    .addHeader("x-functions-key", FUNCTION_KEY)
                    .addHeader("Content-Type", "application/json")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = httpClient.newCall(request).execute()
                val responseBody = response.body?.string()
                Log.d(TAG, "Register response: ${response.code} - $responseBody")

                if (response.isSuccessful) {
                    Log.d(TAG, "Device registration successful")
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("HTTP ${response.code}: $responseBody"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Device registration error", e)
                Result.failure(e)
            }
        }
    }
}
