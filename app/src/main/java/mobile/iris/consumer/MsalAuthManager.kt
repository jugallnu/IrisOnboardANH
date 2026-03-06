package mobile.iris.consumer

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import android.util.Log
import java.security.MessageDigest
import com.microsoft.identity.client.AcquireTokenParameters
import com.microsoft.identity.client.AuthenticationCallback
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.IMultipleAccountPublicClientApplication
import com.microsoft.identity.client.IPublicClientApplication
import com.microsoft.identity.client.Prompt
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.SilentAuthenticationCallback
import com.microsoft.identity.client.exception.MsalException

class MsalAuthManager(private val context: Context) {

    companion object {
        private const val TAG = "MsalAuthManager"
        val SCOPES = arrayOf("User.Read", "openid", "profile")
    }

    private var msalApp: IMultipleAccountPublicClientApplication? = null
    private var currentAccount: com.microsoft.identity.client.IAccount? = null

    private fun logActualBrokerRedirectUri() {
        try {
            @Suppress("DEPRECATION")
            val firstSig = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager
                    .getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                    .signingInfo
                    ?.apkContentsSigners
                    ?.firstOrNull()
            } else {
                context.packageManager
                    .getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                    .signatures
                    ?.firstOrNull()
            } ?: return
            val md = MessageDigest.getInstance("SHA")
            md.update(firstSig.toByteArray())
            val hash = Base64.encodeToString(md.digest(), Base64.URL_SAFE or Base64.NO_WRAP)
            Log.i(TAG, "══════════════════════════════════════════")
            Log.i(TAG, "BROKER REDIRECT URI DIAGNOSTIC")
            Log.i(TAG, "  Computed from installed APK signature:")
            Log.i(TAG, "  msauth://${context.packageName}/$hash")
            Log.i(TAG, "  msal_config.json has:")
            Log.i(TAG, "  msauth://mobile.iris.consumer/q1ZuSDiheQnSHl3fNRDsAM2XgFk=")
            Log.i(TAG, "══════════════════════════════════════════")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compute broker redirect URI", e)
        }
    }

    fun initialize(onSuccess: () -> Unit, onError: (Exception) -> Unit) {
        logActualBrokerRedirectUri()
        PublicClientApplication.createMultipleAccountPublicClientApplication(
            context,
            R.raw.msal_config,
            object : IPublicClientApplication.IMultipleAccountApplicationCreatedListener {
                override fun onCreated(application: IMultipleAccountPublicClientApplication) {
                    msalApp = application
                    Log.d(TAG, "MSAL initialized successfully")
                    loadAccount()
                    onSuccess()
                }

                override fun onError(exception: MsalException) {
                    Log.e(TAG, "MSAL initialization failed", exception)
                    onError(exception)
                }
            }
        )
    }

    private fun loadAccount() {
        msalApp?.getAccounts(object : IPublicClientApplication.LoadAccountsCallback {
            override fun onTaskCompleted(result: List<com.microsoft.identity.client.IAccount>) {
                currentAccount = result.firstOrNull()
                if (currentAccount != null) {
                    Log.d(TAG, "Account loaded: ${currentAccount?.username}")
                } else {
                    Log.d(TAG, "No account currently signed in")
                }
            }

            override fun onError(exception: MsalException) {
                Log.e(TAG, "Error loading accounts", exception)
            }
        })
    }

    fun signIn(
        activity: Activity,
        onSuccess: (UserInfo) -> Unit,
        onError: (Exception) -> Unit,
        onCancel: () -> Unit
    ) {
        val app = msalApp
        if (app == null) {
            onError(IllegalStateException("MSAL not initialized. Call initialize() first."))
            return
        }

        // Always show account picker so the user can choose or switch accounts.
        launchInteractiveSignIn(app, activity, onSuccess, onError, onCancel)
    }

    private fun launchInteractiveSignIn(
        app: IMultipleAccountPublicClientApplication,
        activity: Activity,
        onSuccess: (UserInfo) -> Unit,
        onError: (Exception) -> Unit,
        onCancel: () -> Unit
    ) {
        val params = AcquireTokenParameters.Builder()
            .startAuthorizationFromActivity(activity)
            .withScopes(SCOPES.toList())
            .withPrompt(Prompt.SELECT_ACCOUNT)
            .withCallback(object : AuthenticationCallback {
                override fun onSuccess(authenticationResult: IAuthenticationResult) {
                    currentAccount = authenticationResult.account
                    val claims = mergedClaims(authenticationResult.accessToken, authenticationResult.account)
                    val email = resolveEmail(authenticationResult.account, claims)
                    val userId = resolveUserId(claims)
                    val userInfo = UserInfo(
                        name = claims["name"] as? String,
                        email = email,
                        accessToken = authenticationResult.accessToken,
                        userId = userId
                    )
                    Log.d(TAG, "Sign in successful: ${userInfo.email}, userId=$userId")
                    onSuccess(userInfo)
                }

                override fun onError(exception: MsalException) {
                    Log.e(TAG, "Sign in failed", exception)
                    onError(exception)
                }

                override fun onCancel() {
                    Log.d(TAG, "Sign in cancelled")
                    onCancel()
                }
            })
            .build()

        app.acquireToken(params)
    }

    fun signOut(onSuccess: () -> Unit, onError: (Exception) -> Unit) {
        val app = msalApp
        if (app == null) {
            onError(IllegalStateException("MSAL not initialized"))
            return
        }

        val account = currentAccount
        if (account == null) {
            onSuccess()
            return
        }

        app.removeAccount(account, object : IMultipleAccountPublicClientApplication.RemoveAccountCallback {
            override fun onRemoved() {
                currentAccount = null
                Log.d(TAG, "Sign out successful")
                onSuccess()
            }

            override fun onError(exception: MsalException) {
                Log.e(TAG, "Sign out failed", exception)
                onError(exception)
            }
        })
    }

    fun isSignedIn(): Boolean = currentAccount != null

    fun getCurrentUsername(): String? = currentAccount?.username

    /**
     * Decodes the payload of a JWT token (base64url) and returns its claims as a map.
     * Does not verify the signature — used only to read claims for display/logging.
     * Returns empty map if the token is opaque (not a JWT).
     */
    private fun decodeJwtClaims(jwt: String): Map<String, Any?> {
        Log.d(TAG, "Token prefix: ${jwt.take(20)}...")
        return try {
            val parts = jwt.split(".")
            if (parts.size < 3) {
                Log.w(TAG, "Token is opaque (not a JWT) — ${parts.size} parts")
                return emptyMap()
            }
            val payload = parts[1]
            val padded = when (payload.length % 4) {
                2 -> "$payload=="
                3 -> "$payload="
                else -> payload
            }
            val decoded = android.util.Base64.decode(padded, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
            val json = org.json.JSONObject(String(decoded, Charsets.UTF_8))
            json.keys().asSequence().associateWith { json.opt(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode JWT claims", e)
            emptyMap()
        }
    }

    /** Merges access token claims with ID token claims from account.claims (ID token wins on conflict). */
    private fun mergedClaims(
        accessToken: String,
        account: com.microsoft.identity.client.IAccount
    ): Map<String, Any?> {
        val atClaims = decodeJwtClaims(accessToken)
        val idClaims = account.claims ?: emptyMap()
        Log.d(TAG, "Access token claims count: ${atClaims.size}, ID token claims count: ${idClaims.size}")
        return atClaims + idClaims   // ID token claims override access token on conflict
    }

    /**
     * Resolves email from account + decoded JWT claims, falling back through multiple claim names.
     */
    private fun resolveEmail(account: com.microsoft.identity.client.IAccount, claims: Map<String, Any?>): String {
        val username = account.username
        if (!username.isNullOrBlank() && !username.contains("Missing")) return username
        return (claims["preferred_username"]
            ?: claims["email"]
            ?: claims["upn"]
            ?: claims["unique_name"]) as? String
            ?: "Unknown"
    }

    /**
     * Extracts OID (MSAID) from decoded JWT claims and logs all claims.
     * OID is the user's stable unique identifier — present for both MSA and AAD accounts.
     */
    private fun resolveUserId(claims: Map<String, Any?>): String? {
        Log.i(TAG, "══ TOKEN CLAIMS ══════════════════════════════")
        claims.forEach { (key, value) -> Log.i(TAG, "  $key = $value") }
        Log.i(TAG, "═════════════════════════════════════════════")

        val userId = claims["oid"] as? String
        Log.i(TAG, "  userId (oid/msaid) = $userId")
        return userId
    }
}

data class UserInfo(
    val name: String? = null,      // 'name' claim from JWT (display name)
    val email: String = "",
    val accessToken: String = "",
    val userId: String? = null     // OID (MSAID) — stable unique identifier for the user
)
