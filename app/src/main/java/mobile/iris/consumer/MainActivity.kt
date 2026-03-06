package mobile.iris.consumer

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
import mobile.iris.consumer.ui.theme.IrisOnboardANHTheme

class MainActivity : ComponentActivity() {
    private val TAG = "MainActivity"
    private lateinit var msalAuthManager: MsalAuthManager
    private lateinit var deviceRegistrationManager: DeviceRegistrationManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        msalAuthManager = MsalAuthManager(this)
        deviceRegistrationManager = DeviceRegistrationManager(this)

        enableEdgeToEdge()
        setContent {
            IrisOnboardANHTheme {
                var fcmToken by remember { mutableStateOf("Fetching...") }
                var userInfo by remember { mutableStateOf<UserInfo?>(null) }
                var isLoading by remember { mutableStateOf(false) }
                var isMsalInitialized by remember { mutableStateOf(false) }
                var registrationStatus by remember { mutableStateOf<String?>(null) }
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
                            fcmToken = "Error fetching token"
                            return@addOnCompleteListener
                        }
                        fcmToken = task.result
                        Log.d(TAG, "FCM Registration ID: $fcmToken")
                    }
                }

                // Trigger registration once both FCM token and user are available
                LaunchedEffect(userInfo, fcmToken) {
                    val info = userInfo ?: return@LaunchedEffect
                    if (fcmToken == "Fetching..." || fcmToken == "Error fetching token") return@LaunchedEffect

                    registrationStatus = "Registering..."

                    val result = deviceRegistrationManager.register(
                        fcmToken = fcmToken,
                        userId  = info.userId
                    )
                    registrationStatus = if (result.isSuccess) "Registered" else "Failed: ${result.exceptionOrNull()?.message}"
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainContent(
                        fcmToken = fcmToken,
                        userInfo = userInfo,
                        isLoading = isLoading,
                        isMsalInitialized = isMsalInitialized,
                        registrationStatus = registrationStatus,
                        onSignIn = {
                            isLoading = true
                            msalAuthManager.signIn(
                                activity = activity,
                                onSuccess = { info ->
                                    userInfo = info
                                    isLoading = false
                                    Toast.makeText(context, "Welcome ${info.name ?: info.email}!", Toast.LENGTH_SHORT).show()
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
                                    registrationStatus = null
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
    registrationStatus: String?,
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

        MicrosoftLoginCard(
            userInfo = userInfo,
            isLoading = isLoading,
            isMsalInitialized = isMsalInitialized,
            onSignIn = onSignIn,
            onSignOut = onSignOut
        )

        if (registrationStatus != null) {
            RegistrationStatusCard(status = registrationStatus)
        }

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
                Text(
                    text = "Welcome ${userInfo.name ?: userInfo.email}.",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF0078D4)
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(onClick = onSignOut) {
                    Text("Sign Out")
                }
            } else {
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
fun RegistrationStatusCard(status: String) {
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
                text = "Device Registration",
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
