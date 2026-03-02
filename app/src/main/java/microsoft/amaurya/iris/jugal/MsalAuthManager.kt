package microsoft.amaurya.iris.jugal

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
import com.microsoft.identity.client.IPublicClientApplication
import com.microsoft.identity.client.ISingleAccountPublicClientApplication
import com.microsoft.identity.client.Prompt
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.SilentAuthenticationCallback
import com.microsoft.identity.client.exception.MsalException
import com.microsoft.identity.client.exception.MsalUiRequiredException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Manager class for handling Microsoft Authentication using MSAL
 */
class MsalAuthManager(private val context: Context) {

    companion object {
        private const val TAG = "MsalAuthManager"

        // Scopes for Microsoft Graph API
        val SCOPES = arrayOf("User.Read")

        // Scope for the ICP registration API — token will contain aud, tid, oid claims
        val REGISTRATION_SCOPES = arrayOf("https://register.icp.ideas.microsoft.com/.default")
    }

    private var msalApp: ISingleAccountPublicClientApplication? = null
    private var currentAccount: com.microsoft.identity.client.IAccount? = null

    /**
     * Logs the broker redirect URI computed from the APK's actual signing certificate.
     * Compare the logged URI against msal_config.json and the AAD App Registration —
     * all three must match exactly for broker auth to work.
     */
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
            Log.i(TAG, "  msauth://microsoft.amaurya.iris.jugal/q1ZuSDiheQnSHl3fNRDsAM2XgFk=")
            Log.i(TAG, "  These MUST be identical for broker auth to work.")
            Log.i(TAG, "══════════════════════════════════════════")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compute broker redirect URI", e)
        }
    }

    /**
     * Initialize MSAL - must be called before any other operations
     */
    fun initialize(onSuccess: () -> Unit, onError: (Exception) -> Unit) {
        logActualBrokerRedirectUri()
        PublicClientApplication.createSingleAccountPublicClientApplication(
            context,
            R.raw.msal_config,
            object : IPublicClientApplication.ISingleAccountApplicationCreatedListener {
                override fun onCreated(application: ISingleAccountPublicClientApplication) {
                    msalApp = application
                    Log.d(TAG, "MSAL initialized successfully")

                    // Load existing account if any
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

    /**
     * Load currently signed-in account (if any)
     */
    private fun loadAccount() {
        msalApp?.getCurrentAccountAsync(object : ISingleAccountPublicClientApplication.CurrentAccountCallback {
            override fun onAccountLoaded(activeAccount: com.microsoft.identity.client.IAccount?) {
                currentAccount = activeAccount
                if (activeAccount != null) {
                    Log.d(TAG, "Account loaded: ${activeAccount.username}")
                } else {
                    Log.d(TAG, "No account currently signed in")
                }
            }

            override fun onAccountChanged(priorAccount: com.microsoft.identity.client.IAccount?, currentAccount: com.microsoft.identity.client.IAccount?) {
                this@MsalAuthManager.currentAccount = currentAccount
                Log.d(TAG, "Account changed")
            }

            override fun onError(exception: MsalException) {
                Log.e(TAG, "Error loading account", exception)
            }
        })
    }

    /**
     * Sign in with Microsoft.
     * If an account is already signed in from a previous session, restores it
     * silently instead of launching the sign-in UI again.
     */
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

        // Account already loaded from a previous session — restore silently
        val existingAccount = currentAccount
        if (existingAccount != null) {
            Log.d(TAG, "Account already signed in: ${existingAccount.username}, restoring silently")
            app.acquireTokenSilentAsync(
                SCOPES,
                existingAccount.authority,
                object : SilentAuthenticationCallback {
                    override fun onSuccess(authenticationResult: IAuthenticationResult) {
                        val userInfo = UserInfo(
                            displayName = existingAccount.username,
                            email = existingAccount.username,
                            accessToken = authenticationResult.accessToken
                        )
                        Log.d(TAG, "Session restored for: ${userInfo.email}")
                        onSuccess(userInfo)
                    }

                    override fun onError(exception: MsalException) {
                        // Silent restore failed — fall through to interactive sign-in
                        Log.w(TAG, "Silent restore failed, launching interactive sign-in", exception)
                        launchInteractiveSignIn(app, activity, onSuccess, onError, onCancel)
                    }
                }
            )
            return
        }

        launchInteractiveSignIn(app, activity, onSuccess, onError, onCancel)
    }

    private fun launchInteractiveSignIn(
        app: ISingleAccountPublicClientApplication,
        activity: Activity,
        onSuccess: (UserInfo) -> Unit,
        onError: (Exception) -> Unit,
        onCancel: () -> Unit
    ) {
        // Use acquireToken with Prompt.LOGIN instead of signIn(SignInParameters).
        // signIn() resolves via the Android account-picker SSO shortcut, which picks an
        // existing Authenticator account but does NOT create a per-clientId brokered session.
        // acquireToken + LOGIN forces the broker to run a full interactive auth flow,
        // which registers the account under this clientId in the broker cache and attaches
        // device compliance claims — required for the REGISTRATION_SCOPES token to succeed.
        val params = AcquireTokenParameters.Builder()
            .startAuthorizationFromActivity(activity)
            .withScopes(SCOPES.toList())
            .withPrompt(Prompt.LOGIN)
            .withCallback(object : AuthenticationCallback {
                override fun onSuccess(authenticationResult: IAuthenticationResult) {
                    currentAccount = authenticationResult.account
                    val userInfo = UserInfo(
                        displayName = authenticationResult.account.username,
                        email = authenticationResult.account.username,
                        accessToken = authenticationResult.accessToken
                    )
                    Log.d(TAG, "Sign in successful: ${userInfo.email}")
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

    /**
     * Acquires a JWE token for the ICP registration API
     * (resource: https://register.icp.ideas.microsoft.com).
     *
     * Azure AD returns a token whose claims include:
     *   - aud  → https://register.icp.ideas.microsoft.com
     *   - tid  → tenant ID of the signed-in user
     *   - oid  → object ID of the signed-in user
     *
     * Tries silent acquisition first. If AAD requires broker-based device compliance
     * verification (MsalUiRequiredException / AADSTS530003), the local MSAL cache is
     * cleared and [BrokerSignInRequiredException] is thrown. The caller must then reset
     * to the sign-in screen so the user signs in fresh — with a clean cache, MSAL will
     * route through the Authenticator broker (DEFAULT user-agent prefers broker), which
     * presents device compliance state to AAD and unblocks token acquisition.
     *
     * Must be called from a coroutine.
     */
    suspend fun acquireJweTokenForRegistration(): String =
        suspendCancellableCoroutine { continuation ->
            val app = msalApp
            val account = currentAccount

            if (app == null) {
                continuation.resumeWithException(IllegalStateException("MSAL not initialized"))
                return@suspendCancellableCoroutine
            }
            if (account == null) {
                continuation.resumeWithException(IllegalStateException("No account signed in"))
                return@suspendCancellableCoroutine
            }

            fun signOutAndRequireBrokerSignIn(cause: MsalException) {
                // The local cache holds a browser-based token that the broker doesn't know
                // about (evidenced by "No accounts found for clientId" from the broker).
                // Wipe the local cache so the next sign-in goes through the broker.
                Log.w(TAG, "Clearing stale local cache — broker sign-in required for device compliance", cause)
                app.signOut(object : ISingleAccountPublicClientApplication.SignOutCallback {
                    override fun onSignOut() {
                        currentAccount = null
                        continuation.resumeWithException(BrokerSignInRequiredException())
                    }
                    override fun onError(e: MsalException) {
                        // Sign-out failed but still propagate the broker-required exception.
                        currentAccount = null
                        continuation.resumeWithException(BrokerSignInRequiredException())
                    }
                })
            }

            app.acquireTokenSilentAsync(
                REGISTRATION_SCOPES,
                account.authority,
                object : SilentAuthenticationCallback {
                    override fun onSuccess(authenticationResult: IAuthenticationResult) {
                        Log.d(TAG, "JWE registration token acquired silently for ${account.username}")
                        continuation.resume(authenticationResult.accessToken)
                    }

                    override fun onError(exception: MsalException) {
                        when {
                            // MsalUiRequiredException / AADSTS530003 = broker needs to verify
                            // device compliance but the current local session bypassed the broker.
                            // Clear the stale cache and force a fresh broker-based sign-in.
                            exception is MsalUiRequiredException ||
                            exception.message?.contains("AADSTS530003") == true -> {
                                signOutAndRequireBrokerSignIn(exception)
                            }
                            // AADSTS70045 = sign-in frequency CA policy — token too old.
                            // Must sign out to clear the stale token and force fresh sign-in.
                            exception.message?.contains("AADSTS70045") == true -> {
                                Log.w(TAG, "Sign-in frequency policy triggered — clearing stale token", exception)
                                app.signOut(object : ISingleAccountPublicClientApplication.SignOutCallback {
                                    override fun onSignOut() {
                                        currentAccount = null
                                        continuation.resumeWithException(SessionExpiredException())
                                    }
                                    override fun onError(e: MsalException) {
                                        continuation.resumeWithException(SessionExpiredException())
                                    }
                                })
                            }
                            else -> {
                                Log.e(TAG, "Failed to acquire JWE registration token", exception)
                                continuation.resumeWithException(exception)
                            }
                        }
                    }
                }
            )
        }

    /**
     * Acquire token silently (for already signed-in users)
     */
    fun acquireTokenSilent(
        onSuccess: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val app = msalApp
        val account = currentAccount

        if (app == null) {
            onError(IllegalStateException("MSAL not initialized"))
            return
        }

        if (account == null) {
            onError(IllegalStateException("No account signed in"))
            return
        }

        app.acquireTokenSilentAsync(
            SCOPES,
            account.authority,
            object : SilentAuthenticationCallback {
                override fun onSuccess(authenticationResult: IAuthenticationResult) {
                    Log.d(TAG, "Token acquired silently")
                    onSuccess(authenticationResult.accessToken)
                }

                override fun onError(exception: MsalException) {
                    Log.e(TAG, "Silent token acquisition failed", exception)
                    onError(exception)
                }
            }
        )
    }

    /**
     * Sign out from Microsoft account
     */
    fun signOut(onSuccess: () -> Unit, onError: (Exception) -> Unit) {
        val app = msalApp
        if (app == null) {
            onError(IllegalStateException("MSAL not initialized"))
            return
        }

        app.signOut(object : ISingleAccountPublicClientApplication.SignOutCallback {
            override fun onSignOut() {
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

    /**
     * Check if user is currently signed in
     */
    fun isSignedIn(): Boolean = currentAccount != null

    /**
     * Get current account username
     */
    fun getCurrentUsername(): String? = currentAccount?.username
}

/**
 * Data class to hold user information after successful authentication
 */
data class UserInfo(
    val displayName: String,
    val email: String,
    val accessToken: String
)

/**
 * Thrown when the cached token is rejected due to a sign-in frequency
 * Conditional Access policy (AADSTS70045). The stale token has been cleared —
 * the user must sign in again to continue.
 */
class SessionExpiredException : Exception("Session expired. Please sign in again.")

/**
 * Thrown when the silent token acquisition for a compliance-gated resource fails because
 * the existing local MSAL session bypassed the Authenticator broker (e.g. the initial
 * sign-in went through the browser tab instead of the broker).
 *
 * The stale local cache has been cleared. The caller should reset to the sign-in screen
 * so the user can sign in fresh — with an empty cache, MSAL (DEFAULT user-agent) will
 * route through the Authenticator broker, which passes device compliance state to AAD.
 */
class BrokerSignInRequiredException : Exception(
    "Please sign in again. Your session needs to be verified through Microsoft Authenticator for device compliance."
)
