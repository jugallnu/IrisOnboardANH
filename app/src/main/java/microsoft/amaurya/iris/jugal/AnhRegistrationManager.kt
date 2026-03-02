package microsoft.amaurya.iris.jugal

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

class AnhRegistrationManager(private val context: Context) {

    companion object {
        private const val TAG = "AnhRegistrationManager"

        private const val ACCOUNT_NAME = "IrisMobileAndroidANH"
        private const val PLATFORM_FCM_V1 = "FcmV1"

        private const val PREFS_NAME = "anh_prefs"
        private const val KEY_INSTALLATION_ID = "installation_id"

        private const val BASE_URL = "https://mucp.api.account.microsoft.com"

        // App package name — must match the entry registered in mucpevents.ini allowlist
        private const val APP_ID = "microsoft.amaurya.iris.jugal"
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
                Log.d(TAG, "Generated new InstallationId: $newId")
            }
    }

    /**
     * Registers the device with Azure Notification Hub via the ICP register API.
     *
     * @param fcmToken  FCM registration token (the "handle")
     * @param jweToken  JWE token acquired from AAD for resource
     *                  https://register.icp.ideas.microsoft.com — contains aud, tid, oid claims
     */
    suspend fun register(fcmToken: String, jweToken: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val installationId = getInstallationId()
                val locale = Locale.getDefault().toLanguageTag()

                val payload = JSONObject().apply {
                    put("accountName", ACCOUNT_NAME)
                    put("platform", PLATFORM_FCM_V1)
                    put("installationId", installationId)
                    put("handle", fcmToken)
                    put("locale", locale)
                }

                Log.d(TAG, "Registering: installationId=$installationId, locale=$locale, platform=$PLATFORM_FCM_V1")

                val request = Request.Builder()
                    .url("$BASE_URL/applications/v1/anhregistercommercial")
                    .addHeader("Authorization", "Application application_id=\"$APP_ID\", bearer_token=\"$jweToken\"")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("MS-CV", UUID.randomUUID().toString())
                    .addHeader("client-request-id", UUID.randomUUID().toString())
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = httpClient.newCall(request).execute()
                val responseBody = response.body?.string()
                Log.d(TAG, "Register response: ${response.code} - $responseBody")

                if (response.isSuccessful) {
                    Log.d(TAG, "ANH registration successful")
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("HTTP ${response.code}: $responseBody"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "ANH registration error", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Deletes the device registration from Azure Notification Hub.
     * Call this on sign-out so the device stops receiving push notifications.
     */
    suspend fun deleteRegistration(): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val installationId = context
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(KEY_INSTALLATION_ID, null)
                    ?: return@withContext Result.failure(Exception("No installationId found — device was never registered"))

                val url = "$BASE_URL/anh/register?anhAccountName=$ACCOUNT_NAME&installationId=$installationId"

                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer <token>") // TODO: same as register
                    .delete()
                    .build()

                val response = httpClient.newCall(request).execute()
                Log.d(TAG, "Delete registration response: ${response.code}")

                if (response.isSuccessful) {
                    Log.d(TAG, "ANH registration deleted")
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("HTTP ${response.code}: ${response.body?.string()}"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Delete registration error", e)
                Result.failure(e)
            }
        }
    }
}
