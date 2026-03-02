package microsoft.amaurya.iris.jugal

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import microsoft.amaurya.iris.jugal.ui.theme.IrisOnboardANHTheme

class MainActivity : ComponentActivity() {
    private val TAG = "MainActivity"
    private lateinit var msalAuthManager: MsalAuthManager
    private lateinit var anhRegistrationManager: AnhRegistrationManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize MSAL Auth Manager
        msalAuthManager = MsalAuthManager(this)
        anhRegistrationManager = AnhRegistrationManager(this)
        
        enableEdgeToEdge()
        setContent {
            IrisOnboardANHTheme {
                var registrationId by remember { mutableStateOf("Fetching...") }
                var userInfo by remember { mutableStateOf<UserInfo?>(null) }
                var isLoading by remember { mutableStateOf(false) }
                var isMsalInitialized by remember { mutableStateOf(false) }
                var anhRegistrationStatus by remember { mutableStateOf<String?>(null) }
                val context = LocalContext.current
                val activity = this

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { isGranted ->
                    if (isGranted) {
                        Log.d(TAG, "Notification permission granted")
                    }
                }

                LaunchedEffect(Unit) {
                    // Initialize MSAL
                    msalAuthManager.initialize(
                        onSuccess = {
                            isMsalInitialized = true
                            Log.d(TAG, "MSAL initialized")
                        },
                        onError = { error ->
                            Log.e(TAG, "MSAL init error: ${error.message}")
                            Toast.makeText(context, "MSAL init failed: ${error.message}", Toast.LENGTH_SHORT).show()
                        }
                    )

                    // Request Notification Permission for Android 13+
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }

                    FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                        if (!task.isSuccessful) {
                            Log.w(TAG, "Fetching FCM registration token failed", task.exception)
                            registrationId = "Error fetching token"
                            return@addOnCompleteListener
                        }

                        val token = task.result
                        registrationId = token
                        Log.d(TAG, "FCM Registration ID: $token")
                    }
                }

                // Trigger ANH registration once both FCM token and user are available
                LaunchedEffect(userInfo, registrationId) {
                    val info = userInfo ?: return@LaunchedEffect
                    if (registrationId == "Fetching..." || registrationId == "Error fetching token") return@LaunchedEffect

                    anhRegistrationStatus = "Registering..."

                    // Acquire a JWE token from AAD for the ICP registration resource.
                    // The token contains aud, tid, and oid claims required by the API.
                    val jweToken = runCatching {
                        msalAuthManager.acquireJweTokenForRegistration()
                    }.getOrElse { e ->
                        Log.e(TAG, "Failed to acquire JWE token", e)
                        when (e) {
                            is SessionExpiredException -> {
                                // Sign-in frequency CA policy — user has been signed out.
                                // Registration already completed previously so no action needed.
                                userInfo = null
                                anhRegistrationStatus = null
                            }
                            is BrokerSignInRequiredException -> {
                                // The previous session bypassed the Authenticator broker and the
                                // local cache has now been cleared. Prompt the user to sign in
                                // again — MSAL will route through the broker this time, which
                                // passes device compliance state to AAD.
                                userInfo = null
                                anhRegistrationStatus = null
                                Toast.makeText(context, e.message, Toast.LENGTH_LONG).show()
                            }
                            else -> {
                                anhRegistrationStatus = "Failed: ${e.message}"
                            }
                        }
                        return@LaunchedEffect
                    }

                    val result = anhRegistrationManager.register(
                        fcmToken = registrationId,
                        jweToken = jweToken
                    )
                    anhRegistrationStatus = if (result.isSuccess) "Registered" else "Failed: ${result.exceptionOrNull()?.message}"
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainContent(
                        fcmToken = registrationId,
                        userInfo = userInfo,
                        isLoading = isLoading,
                        isMsalInitialized = isMsalInitialized,
                        anhRegistrationStatus = anhRegistrationStatus,
                        onSignIn = {
                            isLoading = true
                            msalAuthManager.signIn(
                                activity = activity,
                                onSuccess = { info ->
                                    userInfo = info
                                    isLoading = false
                                    Toast.makeText(context, "Welcome ${info.displayName}!", Toast.LENGTH_SHORT).show()
                                },
                                onError = { error ->
                                    isLoading = false
                                    Toast.makeText(context, "Sign in failed: ${error.message}", Toast.LENGTH_SHORT).show()
                                },
                                onCancel = {
                                    isLoading = false
                                    Toast.makeText(context, "Sign in cancelled", Toast.LENGTH_SHORT).show()
                                }
                            )
                        },
                        onSignOut = {
                            isLoading = true
                            msalAuthManager.signOut(
                                onSuccess = {
                                    userInfo = null
                                    isLoading = false
                                    Toast.makeText(context, "Signed out successfully", Toast.LENGTH_SHORT).show()
                                },
                                onError = { error ->
                                    isLoading = false
                                    Toast.makeText(context, "Sign out failed: ${error.message}", Toast.LENGTH_SHORT).show()
                                }
                            )
                        },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun MainContent(
    fcmToken: String,
    userInfo: UserInfo?,
    isLoading: Boolean,
    isMsalInitialized: Boolean,
    anhRegistrationStatus: String?,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        // Microsoft Login Section
        MicrosoftLoginCard(
            userInfo = userInfo,
            isLoading = isLoading,
            isMsalInitialized = isMsalInitialized,
            onSignIn = onSignIn,
            onSignOut = onSignOut
        )

        // ANH Registration Status (visible after sign-in)
        if (anhRegistrationStatus != null) {
            AnhRegistrationCard(status = anhRegistrationStatus)
        }

        // FCM Token Section
        FcmTokenCard(token = fcmToken)
    }
}

@Composable
fun MicrosoftLoginCard(
    userInfo: UserInfo?,
    isLoading: Boolean,
    isMsalInitialized: Boolean,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Microsoft Login",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            if (isLoading) {
                CircularProgressIndicator()
            } else if (userInfo != null) {
                // Signed in state
                Text(
                    text = "Welcome!",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF0078D4)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = userInfo.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = userInfo.email,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(onClick = onSignOut) {
                    Text("Sign Out")
                }
            } else {
                // Signed out state
                Text(
                    text = "Sign in with your Microsoft account",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onSignIn,
                    enabled = isMsalInitialized,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF0078D4)
                    )
                ) {
                    Text("Sign in with Microsoft")
                }
                if (!isMsalInitialized) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Initializing...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun AnhRegistrationCard(status: String) {
    val isRegistered = status == "Registered"
    val isRegistering = status == "Registering..."

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "ANH Registration",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (isRegistering) {
                CircularProgressIndicator()
            } else {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isRegistered) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun FcmTokenCard(token: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "FCM Registration ID",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            SelectionContainer {
                Text(
                    text = token,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "(Long press to copy)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}