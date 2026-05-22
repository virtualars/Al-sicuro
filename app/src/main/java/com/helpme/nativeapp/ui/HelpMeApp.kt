package it.alsicuro.virtualars.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import it.alsicuro.virtualars.data.AppSettings
import it.alsicuro.virtualars.data.SosContact
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

private val comfortQuotes = listOf(
    "La tua presenza," to "illumina tutta la stanza",
    "Ammiriamo" to "la tua forte personalità",
    "Ti siamo vicini" to "in ogni modo possibile",
    "Tu sei forte" to "e non sei sola"
)

private val nearbyPlaces = listOf(
    "Questura vicino a me",
    "Carabinieri vicino a me",
    "Ospedale vicino a me",
    "Farmacia vicino a me",
    "Stazione autobus vicino a me",
    "Centro antiviolenza vicino a me",
    "Bagno pubblico vicino a me"
)

private val panelShape = RoundedCornerShape(24.dp)
private val panelColor = Color(0xFFFFFDFD)

// Entry point Compose dell'app:
// aspetta il caricamento dello stato locale, gestisce onboarding e dashboard
// e mantiene separata la logica dei permessi dalla logica di navigazione.
@Composable
fun HelpMeApp(vm: HelpMeViewModel) {
    val settings by vm.loadedSettings.collectAsState()
    val selectedTab by vm.selectedTab.collectAsState()

    MaterialTheme {
        when (val current = settings) {
            null -> SplashLoading()
            else -> PermissionSupervisor {
                if (!current.onboardingSeen) {
                    OnboardingScreen(onDone = vm::markOnboardingSeen)
                } else {
                    MainDashboard(vm = vm, selectedTab = selectedTab, settings = current)
                }
            }
        }
    }
}

@Composable
private fun PermissionSupervisor(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var activePrompt by remember { mutableStateOf<PermissionPrompt?>(null) }
    var dismissedInSession by remember { mutableStateOf(setOf<String>()) }

    val requestPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        activePrompt = null
    }
    val openSettingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        activePrompt = null
    }

    // I prompt vengono mostrati uno alla volta per rendere il flusso più leggibile.
    fun missingPrompts(): List<PermissionPrompt> {
        return permissionPrompts().filter { prompt ->
            !isGranted(context, prompt.permission) && !dismissedInSession.contains(prompt.permission)
        }
    }

    fun refreshPromptQueue() {
        if (activePrompt == null) {
            activePrompt = missingPrompts().firstOrNull()
        }
    }

    LaunchedEffect(dismissedInSession) {
        refreshPromptQueue()
    }

    DisposableEffect(lifecycleOwner, dismissedInSession, activePrompt) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshPromptQueue()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    content()

    activePrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = {
                dismissedInSession = dismissedInSession + prompt.permission
                activePrompt = missingPrompts().firstOrNull()
            },
            title = { Text(prompt.title) },
            text = {
                Text(
                    buildString {
                        append(prompt.message)
                        if (prompt.permission == Manifest.permission.ACCESS_BACKGROUND_LOCATION) {
                            append("\n\nPassaggi consigliati:")
                            append("\n1. Apri le impostazioni dell'app")
                            append("\n2. Tocca Autorizzazioni")
                            append("\n3. Apri Posizione")
                            append("\n4. Seleziona Consenti sempre")
                        }
                        append("\n\nSe tutte le autorizzazioni non sono attive, l'app potrebbe non funzionare correttamente.")
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (prompt.openInSettings) {
                        openSettingsLauncher.launch(appSettingsIntent(context))
                    } else {
                        requestPermissionLauncher.launch(prompt.permission)
                    }
                }) {
                    Text("Attiva")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    dismissedInSession = dismissedInSession + prompt.permission
                    activePrompt = missingPrompts().firstOrNull()
                }) {
                    Text("Più tardi")
                }
            }
        )
    }
}

@Composable
private fun SplashLoading() {
    Surface(color = Color(0xFFC91111), modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AssetImage(assetName = "iconhelpme.png", modifier = Modifier.size(180.dp), contentScale = ContentScale.Fit)
            Spacer(Modifier.height(20.dp))
            Text("Al Sicuro", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(8.dp))
            Text("Non aver paura di chiedere aiuto!", color = Color.White)
            Spacer(Modifier.height(24.dp))
            CircularProgressIndicator(color = Color.White)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainDashboard(vm: HelpMeViewModel, selectedTab: BottomTab, settings: AppSettings) {
    // Dashboard principale: qui si compone l'intera esperienza utente
    // e si instradano le azioni verso Home, Contatti e Impostazioni.
    val context = LocalContext.current
    val accelerometerAvailable = remember { vm.hasAccelerometer() }
    val batteryOptimizationDisabled = remember { vm.isBatteryOptimizationDisabled() }
    var showSettingsPage by rememberSaveable { mutableStateOf(false) }
    var showSosDialog by remember { mutableStateOf(false) }
    var showContactsPermissionDialog by remember { mutableStateOf(false) }

    val pickContactLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri: Uri? ->
        uri?.let { vm.addContactFromUri(it.toString()) }
    }

    if (showSettingsPage) {
        SettingsScreen(
            settings = settings,
            onBack = { showSettingsPage = false },
            onToggleEscort = vm::setEscortMode,
            onToggleShake = vm::setShakeMode,
            onOpenPermissionsSettings = { vm.openPermissionsSettings(context) },
            onOpenBatterySettings = { vm.openBatteryOptimizationSettings(context) },
            batteryOptimizationDisabled = batteryOptimizationDisabled,
            accelerometerAvailable = accelerometerAvailable,
            onSendSafeNow = vm::sendSafeNow,
            onStartTimer = { vm.startSosTimer() },
            onCancelTimer = vm::cancelSosTimer,
            onUpdateSmsTemplate = vm::updateSmsTemplate
        )
        BackHandler { showSettingsPage = false }
        return
    }

    Scaffold(
        containerColor = Color(0xFFFAFCFE),
        topBar = {
            if (selectedTab == BottomTab.Contacts) {
                CenterAlignedTopAppBar(
                    title = { Text("Contatti SOS", fontWeight = FontWeight.Black) },
                    actions = {
                        IconButton(onClick = {
                            if (isGranted(context, Manifest.permission.READ_CONTACTS)) {
                                pickContactLauncher.launch(null)
                            } else {
                                showContactsPermissionDialog = true
                            }
                        }) {
                            Icon(Icons.Default.Add, contentDescription = null)
                        }
                    }
                )
            }
        },
        bottomBar = {
            BottomAppBar(containerColor = Color.White) {
                NavigationBarItem(
                    selected = selectedTab == BottomTab.Home,
                    onClick = { vm.selectTab(BottomTab.Home) },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text("Home") }
                )
                NavigationBarItem(
                    selected = selectedTab == BottomTab.Contacts,
                    onClick = { vm.selectTab(BottomTab.Contacts) },
                    icon = { Icon(Icons.Default.Phone, contentDescription = null) },
                    label = { Text("Contatti") }
                )
            }
        }
    ) { innerPadding ->
        if (selectedTab == BottomTab.Home) {
            HomeScreen(
                paddingValues = innerPadding,
                settings = settings,
                onOpenSettings = { showSettingsPage = true },
                onTriggerSos = {
                    vm.triggerManualSos("SOS attivato dalla home.")
                    if (vm.hasSosContacts()) {
                        showSosDialog = true
                    }
                },
                onCall = { number ->
                    context.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")))
                },
                onOpenPlace = vm::openNearbyPlace,
                onToggleEscort = vm::setEscortMode,
                onToggleShake = vm::setShakeMode,
                accelerometerAvailable = accelerometerAvailable,
                onSendSafeNow = vm::sendSafeNow,
                onStartTimer = { vm.startSosTimer() },
                onCancelTimer = vm::cancelSosTimer,
                batteryOptimizationDisabled = batteryOptimizationDisabled,
                onAddContact = {
                    if (isGranted(context, Manifest.permission.READ_CONTACTS)) {
                        pickContactLauncher.launch(null)
                    } else {
                        showContactsPermissionDialog = true
                    }
                }
            )
        } else {
            ContactsScreen(
                modifier = Modifier.padding(innerPadding),
                contacts = settings.contacts,
                onDelete = vm::removeContact,
                onMoveUp = vm::moveContactUp,
                onMoveDown = vm::moveContactDown,
                onAddContact = {
                    if (isGranted(context, Manifest.permission.READ_CONTACTS)) {
                        pickContactLauncher.launch(null)
                    } else {
                        showContactsPermissionDialog = true
                    }
                }
            )
        }
    }

    if (showSosDialog) {
        AlertDialog(
            onDismissRequest = { showSosDialog = false },
            title = { Text("SOS") },
            text = { Text("Richiesta di aiuto inviata correttamente.") },
            confirmButton = {
                Button(onClick = {
                    vm.triggerManualSos("SOS inviato nuovamente.")
                    showSosDialog = false
                }) {
                    Text("Invia di nuovo")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSosDialog = false }) {
                    Text("Chiudi")
                }
            }
        )
    }

    if (showContactsPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showContactsPermissionDialog = false },
            title = { Text("Permesso contatti") },
            text = { Text("Per aggiungere un contatto SOS devi riattivare il permesso contatti dalle impostazioni dell'app.") },
            confirmButton = {
                TextButton(onClick = {
                    showContactsPermissionDialog = false
                    vm.openPermissionsSettings(context)
                }) {
                    Text("Apri impostazioni")
                }
            },
            dismissButton = {
                TextButton(onClick = { showContactsPermissionDialog = false }) {
                    Text("Chiudi")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun HomeScreen(
    paddingValues: PaddingValues,
    settings: AppSettings,
    onOpenSettings: () -> Unit,
    onTriggerSos: () -> Unit,
    onCall: (String) -> Unit,
    onOpenPlace: (String) -> Unit,
    onToggleEscort: (Boolean) -> Unit,
    onToggleShake: (Boolean) -> Unit,
    accelerometerAvailable: Boolean,
    onSendSafeNow: () -> Unit,
    onStartTimer: () -> Unit,
    onCancelTimer: () -> Unit,
    batteryOptimizationDisabled: Boolean,
    onAddContact: () -> Unit
) {
    // La home raccoglie le azioni critiche dell'app in una sola schermata:
    // SOS, numeri rapidi, luoghi vicini, stato protezione e strumenti secondari.
    var quoteIndex by rememberSaveable { mutableStateOf(Random.nextInt(comfortQuotes.size)) }
    var showQuickHelpDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }

    LazyColumn(
        contentPadding = PaddingValues(
            top = paddingValues.calculateTopPadding(),
            bottom = paddingValues.calculateBottomPadding() + 20.dp
        ),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                shape = panelShape,
                colors = CardDefaults.elevatedCardColors(containerColor = panelColor)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(comfortQuotes[quoteIndex].first, color = Color(0xFF6E6A6A), fontSize = 13.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            comfortQuotes[quoteIndex].second,
                            fontSize = 28.sp,
                            lineHeight = 31.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.clickable { quoteIndex = Random.nextInt(comfortQuotes.size) }
                        )
                    }
                    Surface(shape = CircleShape, shadowElevation = 4.dp, color = Color.White) {
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = null, tint = Color(0xFFC91111))
                        }
                    }
                }
            }
        }
        item {
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .clickable { onTriggerSos() },
                shape = panelShape,
                colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFC91111))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(shape = CircleShape, color = Color.White.copy(alpha = 0.18f)) {
                        Icon(
                            Icons.Default.NotificationsActive,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("SOS immediato", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Invia subito una richiesta di aiuto ai contatti SOS con la tua posizione.",
                            color = Color.White.copy(alpha = 0.92f)
                        )
                    }
                }
            }
        }
        item {
            SectionTitle("Hai urgente bisogno di aiuto?")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
            ) {
                EmergencyQuickCard(
                    title = "Numero unico Emergenze",
                    number = "112",
                    colors = listOf(Color(0xFFD00A10), Color(0xFFE4141D), Color.White),
                    onCall = onCall
                )
                Spacer(Modifier.height(10.dp))
                EmergencyQuickCard(
                    title = "Numero Antiviolenza",
                    number = "1522",
                    colors = listOf(Color(0xFF1D75B9), Color(0xFF02A399), Color(0xFF8ABE42)),
                    onCall = onCall
                )
            }
        }
        item {
            SectionTitle("Scopri cosa c'è vicino a te")
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                nearbyPlaces.forEach { place ->
                    AssistChip(
                        onClick = { onOpenPlace(place) },
                        label = { Text(place.replace(" vicino a me", "")) }
                    )
                }
            }
        }
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth().padding(12.dp).clickable { showQuickHelpDialog = true },
                shape = panelShape,
                colors = CardDefaults.elevatedCardColors(containerColor = panelColor)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Richieste di aiuto", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Invia la tua posizione ogni minuto o attiva l'aiuto con scuotimento anche a schermo spento.",
                            color = Color.DarkGray
                        )
                        if (settings.escortModeEnabled || settings.shakeAlertEnabled) {
                            Spacer(Modifier.height(12.dp))
                            Text("Servizi attivi", color = Color(0xFFC91111), fontWeight = FontWeight.Bold)
                        }
                    }
                    Surface(
                        color = Color(0xFFFCE6E2),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.size(88.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.NotificationsActive,
                                contentDescription = null,
                                tint = Color(0xFFC91111),
                                modifier = Modifier.size(38.dp)
                            )
                        }
                    }
                }
            }
        }
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                shape = panelShape,
                colors = CardDefaults.elevatedCardColors(containerColor = panelColor)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    CardSectionHeader(
                        title = "Contatti SOS salvati",
                        subtitle = "Controlla rapidamente chi riceverà le richieste di aiuto."
                    )
                    Spacer(Modifier.height(8.dp))
                    if (settings.contacts.isEmpty()) {
                        Text("Nessun contatto salvato. Apri la sezione Contatti per aggiungerne almeno uno.", color = Color.Gray)
                    } else {
                        settings.contacts.take(3).forEach { contact ->
                            Text("${contact.name} - ${contact.number}", modifier = Modifier.padding(vertical = 3.dp))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onAddContact, modifier = Modifier.fillMaxWidth()) {
                        Text(if (settings.contacts.isEmpty()) "Aggiungi contatto SOS" else "Aggiungi un altro contatto")
                    }
                }
            }
        }
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                shape = panelShape,
                colors = CardDefaults.elevatedCardColors(containerColor = panelColor)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    CardSectionHeader(
                        title = "Widget SOS",
                        subtitle = "Doppio tocco di sicurezza direttamente dalla home del telefono."
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Puoi aggiungere il widget Al Sicuro nella home del telefono. Il primo tocco arma il SOS, il secondo tocco entro 5 secondi invia subito l'SMS ai contatti selezionati.",
                        color = Color.DarkGray
                    )
                }
            }
        }
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                shape = panelShape,
                colors = CardDefaults.elevatedCardColors(containerColor = panelColor)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    CardSectionHeader(
                        title = "Stato protezione",
                        subtitle = "Verifica in pochi secondi se l'app è pronta a intervenire."
                    )
                    Spacer(Modifier.height(8.dp))
                    StatusLine("Contatti SOS", if (settings.contacts.isNotEmpty()) "Pronti" else "Mancanti", ok = settings.contacts.isNotEmpty())
                    StatusLine("Percorso sicuro", if (settings.escortModeEnabled) "Attivo" else "Disattivo", ok = settings.escortModeEnabled)
                    StatusLine("Scuotimento", if (settings.shakeAlertEnabled) "Attivo" else "Disattivo", ok = settings.shakeAlertEnabled)
                    StatusLine("Timer SOS", if (settings.sosTimerActive) "Attivo" else "Disattivo", ok = settings.sosTimerActive)
                    StatusLine("Batteria senza ottimizzazione", if (batteryOptimizationDisabled) "Configurata" else "Da configurare", ok = batteryOptimizationDisabled)
                    StatusLine("Sensore movimento", if (accelerometerAvailable) "Disponibile" else "Non disponibile", ok = accelerometerAvailable)
                }
            }
        }
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                shape = panelShape,
                colors = CardDefaults.elevatedCardColors(containerColor = panelColor)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    CardSectionHeader(
                        title = "Azioni rapide",
                        subtitle = "Comandi immediati per sicurezza, timer e rilevamento."
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(onClick = onSendSafeNow, modifier = Modifier.weight(1f)) {
                            Text("Sono al sicuro")
                        }
                        Button(
                            onClick = if (settings.sosTimerActive) onCancelTimer else onStartTimer,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (settings.sosTimerActive) "Annulla timer" else "Timer SOS")
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    SettingSwitch(
                        title = "SOS con scuotimento",
                        subtitle = if (accelerometerAvailable) {
                            "Mantiene una notifica attiva e ascolta lo scuotimento anche a schermo spento."
                        } else {
                            "Sensore movimento non disponibile su questo dispositivo."
                        },
                        checked = settings.shakeAlertEnabled,
                        enabled = accelerometerAvailable,
                        onCheckedChange = onToggleShake
                    )
                }
            }
        }
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                shape = panelShape,
                colors = CardDefaults.elevatedCardColors(containerColor = panelColor)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Attività recenti", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(Modifier.height(8.dp))
                    if (settings.eventLog.isEmpty()) {
                        Text("Nessun evento registrato.", color = Color.Gray)
                    } else {
                        settings.eventLog.take(5).forEach { event ->
                            Text("${event.type.uppercase()} • ${event.status}", fontWeight = FontWeight.SemiBold)
                            Text(event.details, color = Color.Gray, fontSize = 13.sp)
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).clickable { showAboutDialog = true },
                shape = panelShape,
                colors = CardDefaults.elevatedCardColors(containerColor = panelColor)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Chi siamo", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("Informazioni sull'app, note di sicurezza e crediti del progetto.", color = Color.DarkGray)
                }
            }
        }
    }

    if (showQuickHelpDialog) {
        AlertDialog(
            onDismissRequest = { showQuickHelpDialog = false },
            confirmButton = {
                TextButton(onClick = { showQuickHelpDialog = false }) {
                    Text("Chiudi")
                }
            },
            title = { Text("Richieste di aiuto") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    SettingSwitch(
                        title = "Percorso sicuro",
                        subtitle = "Invia un SMS iniziale e poi un aggiornamento con posizione ogni 60 secondi finché non disattivi.",
                        checked = settings.escortModeEnabled,
                        onCheckedChange = onToggleEscort
                    )
                    Spacer(Modifier.height(12.dp))
                    SettingSwitch(
                        title = "Aiuto con scuotimento",
                        subtitle = "Mantiene un servizio foreground attivo per rilevare lo scuotimento e inviare un SOS automatico.",
                        checked = settings.shakeAlertEnabled,
                        onCheckedChange = onToggleShake
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onSendSafeNow, modifier = Modifier.fillMaxWidth()) {
                        Text("Invia 'Sono al sicuro'")
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = if (settings.sosTimerActive) onCancelTimer else onStartTimer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (settings.sosTimerActive) "Annulla timer SOS" else "Attiva timer SOS")
                    }
                }
            }
        )
    }

    if (showAboutDialog) {
        AboutDialog(onDismiss = { showAboutDialog = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onToggleEscort: (Boolean) -> Unit,
    onToggleShake: (Boolean) -> Unit,
    onOpenPermissionsSettings: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    batteryOptimizationDisabled: Boolean,
    accelerometerAvailable: Boolean,
    onSendSafeNow: () -> Unit,
    onStartTimer: () -> Unit,
    onCancelTimer: () -> Unit,
    onUpdateSmsTemplate: (String) -> Unit
) {
    // Impostazioni volutamente orientate alla diagnosi:
    // qui l'utente deve capire subito se l'app è pronta a funzionare davvero.
    val context = LocalContext.current
    var showAboutDialog by remember { mutableStateOf(false) }
    var showSmsTemplateDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Color(0xFFFAFCFE),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Impostazioni", fontWeight = FontWeight.ExtraBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp)
        ) {
            item {
                Text(
                    "Aiuto con scuotimento",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Attiva il rilevamento dello scuotimento e la condivisione della posizione in background solo quando necessario.",
                    color = Color.DarkGray
                )
                Spacer(Modifier.height(16.dp))
            }
            item {
                SettingSwitch(
                    title = "Percorso sicuro",
                    subtitle = "Condivide automaticamente la tua posizione ogni 60 secondi finché non lo disattivi.",
                    checked = settings.escortModeEnabled,
                    onCheckedChange = onToggleEscort
                )
                Spacer(Modifier.height(12.dp))
            }
            item {
                SettingSwitch(
                    title = "Aiuto con scuotimento",
                    subtitle = "Se il dispositivo viene scosso, viene inviato un SOS automatico ai contatti salvati.",
                    checked = settings.shakeAlertEnabled,
                    onCheckedChange = onToggleShake
                )
                Spacer(Modifier.height(18.dp))
            }
            item {
                ElevatedCard(shape = panelShape, colors = CardDefaults.elevatedCardColors(containerColor = panelColor)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        CardSectionHeader(
                            title = "Autorizzazioni",
                            subtitle = "Controlla quali permessi sono attivi e gestiscili in un unico punto."
                        )
                        Spacer(Modifier.height(8.dp))
                        PermissionStatusLine("Contatti", isGranted(context, Manifest.permission.READ_CONTACTS))
                        PermissionStatusLine("SMS", isGranted(context, Manifest.permission.SEND_SMS))
                        PermissionStatusLine("Localizzazione precisa", isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION))
                        PermissionStatusLine("Localizzazione in background", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isGranted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) else true)
                        PermissionStatusLine("Telefono", isGranted(context, Manifest.permission.CALL_PHONE))
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            PermissionStatusLine("Notifiche", isGranted(context, Manifest.permission.POST_NOTIFICATIONS))
                        }
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onOpenPermissionsSettings, modifier = Modifier.fillMaxWidth()) {
                            Text("Gestisci autorizzazioni")
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            item {
                ElevatedCard(shape = panelShape, colors = CardDefaults.elevatedCardColors(containerColor = panelColor)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        CardSectionHeader(
                            title = "Messaggio SOS",
                            subtitle = "Personalizza il testo inviato ai contatti in caso di emergenza."
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(settings.smsTemplate, color = Color.DarkGray)
                        Spacer(Modifier.height(8.dp))
                        Text("Placeholder disponibili: {reason}, {link}, {newline}", color = Color.Gray, fontSize = 12.sp)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { showSmsTemplateDialog = true }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Edit, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Modifica messaggio")
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            item {
                ElevatedCard(shape = panelShape, colors = CardDefaults.elevatedCardColors(containerColor = panelColor)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        CardSectionHeader(
                            title = "Diagnostica",
                            subtitle = "Informazioni utili per capire se i servizi resteranno affidabili."
                        )
                        Spacer(Modifier.height(8.dp))
                        PermissionStatusLine("Sensore accelerometro", accelerometerAvailable)
                        PermissionStatusLine("Batteria senza ottimizzazione", batteryOptimizationDisabled)
                        PermissionStatusLine("Timer SOS", settings.sosTimerActive)
                        if (!batteryOptimizationDisabled) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Disattivare le ottimizzazioni batteria aiuta i servizi SOS, timer e percorso sicuro a restare affidabili anche a schermo spento.",
                                color = Color.DarkGray
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(onClick = onSendSafeNow, modifier = Modifier.weight(1f)) {
                                Text("Sono al sicuro")
                            }
                            Button(
                                onClick = if (settings.sosTimerActive) onCancelTimer else onStartTimer,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (settings.sosTimerActive) "Annulla timer" else "Timer SOS")
                            }
                        }
                        if (!batteryOptimizationDisabled) {
                            Spacer(Modifier.height(10.dp))
                            Button(
                                onClick = onOpenBatterySettings,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Apri ottimizzazioni batteria")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth().clickable { showAboutDialog = true },
                    shape = panelShape,
                    colors = CardDefaults.elevatedCardColors(containerColor = panelColor)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFFC91111))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Chi siamo", fontWeight = FontWeight.Bold)
                            Text("Informazioni e crediti dell'app", color = Color.Gray)
                        }
                    }
                }
            }
        }
    }

    if (showAboutDialog) {
        AboutDialog(onDismiss = { showAboutDialog = false })
    }

    if (showSmsTemplateDialog) {
        SmsTemplateDialog(
            initialValue = settings.smsTemplate,
            onDismiss = { showSmsTemplateDialog = false },
            onSave = {
                onUpdateSmsTemplate(it)
                showSmsTemplateDialog = false
            }
        )
    }
}

@Composable
private fun PermissionStatusLine(label: String, granted: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(
            if (granted) "Concesso" else "Non concesso",
            color = if (granted) Color(0xFF1B8F3A) else Color(0xFFC91111),
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun StatusLine(label: String, value: String, ok: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, color = if (ok) Color(0xFF1B8F3A) else Color(0xFFC91111), textAlign = TextAlign.End)
    }
}

@Composable
private fun ContactsScreen(
    modifier: Modifier,
    contacts: List<SosContact>,
    onDelete: (SosContact) -> Unit,
    onMoveUp: (SosContact) -> Unit,
    onMoveDown: (SosContact) -> Unit,
    onAddContact: () -> Unit
) {
    // La rubrica SOS è semplice ma ordinata per priorità,
    // perché l'ordine dei contatti viene riusato anche per l'invio degli SMS.
    if (contacts.isEmpty()) {
        Column(
            modifier = modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Non hai aggiunto nessun contatto SOS.",
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Aggiungine almeno uno per usare SOS, timer, percorso sicuro e scuotimento.",
                textAlign = TextAlign.Center,
                color = Color.Gray
            )
            Spacer(Modifier.height(18.dp))
            Button(onClick = onAddContact) {
                Text("Aggiungi contatto SOS")
            }
        }
        return
    }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Ordina i contatti per priorità e rimuovili direttamente dalla lista.", color = Color.Gray)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onAddContact, modifier = Modifier.fillMaxWidth()) {
                    Text("Aggiungi contatto SOS")
                }
            }
        }
        items(contacts.size) { index ->
            val contact = contacts[index]
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                shape = RoundedCornerShape(22.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(40.dp).clip(CircleShape).background(Color(0xFFC91111)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(contact.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(contact.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Surface(
                                color = Color(0xFFFCE6E2),
                                shape = RoundedCornerShape(999.dp)
                            ) {
                                Text(
                                    "Priorità ${index + 1}",
                                    color = Color(0xFFC91111),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(contact.number, color = Color.Gray)
                    }
                    Column {
                        IconButton(onClick = { onMoveUp(contact) }, enabled = index > 0) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = null, tint = if (index > 0) Color(0xFFC91111) else Color.LightGray)
                        }
                        IconButton(onClick = { onMoveDown(contact) }, enabled = index < contacts.lastIndex) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = if (index < contacts.lastIndex) Color(0xFFC91111) else Color.LightGray)
                        }
                    }
                    IconButton(onClick = { onDelete(contact) }) {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFC91111))
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Card {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(4.dp))
                Text(subtitle, color = Color.Gray, fontSize = 13.sp, lineHeight = 18.sp)
            }
            Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun CardSectionHeader(title: String, subtitle: String) {
    Column {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, color = Color(0xFF6F6A6A), fontSize = 13.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun EmergencyCard(title: String, number: String, colors: List<Color>, onCall: (String) -> Unit) {
    Card(
        modifier = Modifier.width(212.dp).height(190.dp).padding(8.dp).clickable { onCall(number) },
        shape = RoundedCornerShape(28.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().background(Brush.linearGradient(colors)).padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Surface(color = Color.White.copy(alpha = 0.45f), shape = CircleShape) {
                Icon(Icons.Default.Call, contentDescription = null, tint = Color.White, modifier = Modifier.padding(12.dp))
            }
            Column {
                Text(title, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, lineHeight = 23.sp, maxLines = 3)
                Spacer(Modifier.height(10.dp))
                Surface(color = Color.White, shape = RoundedCornerShape(999.dp)) {
                    Text(number, color = Color(0xFFC91111), fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun EmergencyQuickCard(title: String, number: String, colors: List<Color>, onCall: (String) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCall(number) },
        shape = RoundedCornerShape(24.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(colors))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(color = Color.White.copy(alpha = 0.25f), shape = CircleShape) {
                Icon(
                    Icons.Default.Call,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.padding(12.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                Spacer(Modifier.height(4.dp))
                Text("Tocca per chiamare subito", color = Color.White.copy(alpha = 0.92f), fontSize = 13.sp)
            }
            Surface(color = Color.White, shape = RoundedCornerShape(999.dp)) {
                Text(
                    number,
                    color = Color(0xFFC91111),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 10.dp))
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Chiudi")
            }
        },
        title = { Text("Chi siamo") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("Al Sicuro è un'app di sicurezza personale che consente di condividere la posizione tramite avvisi SOS e di raggiungere rapidamente servizi utili nelle vicinanze.")
                Spacer(Modifier.height(12.dp))
                Text("VirtualArs", fontWeight = FontWeight.Bold)
                Text("Vittorio Nicoletti - www.virtualars.it - info@virtualars.it")
                Spacer(Modifier.height(12.dp))
                Text("Ringraziamenti", fontWeight = FontWeight.Bold)
                Text("La Piccola Asia Nicoletti, Sonia Amico, Vincenzo Nicoletti, Giusy Spinello, Maria Sottile")
                Spacer(Modifier.height(12.dp))
                Text("© 2022 VirtualArs")
            }
        }
    )
}

@Composable
private fun SmsTemplateDialog(
    initialValue: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Modifica messaggio SOS") },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    maxLines = 8
                )
                Spacer(Modifier.height(8.dp))
                Text("Usa {reason} per il motivo, {link} per la posizione e {newline} per andare a capo.", color = Color.Gray, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(value) }) {
                Text("Salva")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annulla")
            }
        }
    )
}

@Composable
private fun OnboardingScreen(onDone: () -> Unit) {
    // Onboarding breve: deve spiegare la promessa dell'app
    // senza rallentare troppo il primo accesso.
    var pageIndex by rememberSaveable { mutableStateOf(0) }
    val pages = listOf(
        Triple("Benvenuta in Al Sicuro", "Non aver paura di chiedere aiuto.", "intro1.png"),
        Triple("SOS immediato", "Invia automaticamente SMS ai contatti salvati con la tua posizione.", "intro2.png"),
        Triple("Punti sicuri vicini", "Apri rapidamente luoghi utili intorno a te tramite Google Maps.", "intro3.png"),
        Triple("Scuotimento e percorso sicuro", "Attiva i servizi in foreground per emergenze e aggiornamenti periodici.", "shake.png")
    )

    Surface(color = Color.White, modifier = Modifier.fillMaxSize()) {
        Column {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDone) { Text("Salta") }
            }
            Box(modifier = Modifier.weight(1f)) {
                val item = pages[pageIndex]
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    AssetImage(assetName = item.third, modifier = Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(28.dp)), contentScale = ContentScale.Fit)
                    Spacer(Modifier.height(24.dp))
                    Text(item.first, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Text(item.second, color = Color.Gray, textAlign = TextAlign.Center)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { if (pageIndex > 0) pageIndex -= 1 }) {
                    Text("Indietro")
                }
                Button(onClick = { if (pageIndex == pages.lastIndex) onDone() else pageIndex += 1 }) {
                    Text(if (pageIndex == pages.lastIndex) "Inizia" else "Avanti")
                }
            }
        }
    }
}

@Composable
private fun AssetImage(assetName: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val context = LocalContext.current
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, assetName, context) {
        value = withContext(Dispatchers.IO) {
            context.assets.open(assetName).use { input ->
                BitmapFactory.decodeStream(input)?.asImageBitmap()
            }
        }
    }
    if (bitmap != null) {
        Image(bitmap = bitmap!!, contentDescription = null, modifier = modifier, contentScale = contentScale)
    } else {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White.copy(alpha = 0.85f))
        }
    }
}

private fun isGranted(context: android.content.Context, permission: String): Boolean {
    return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

private data class PermissionPrompt(
    val permission: String,
    val title: String,
    val message: String,
    val openInSettings: Boolean = false
)

private fun permissionPrompts(): List<PermissionPrompt> {
    val prompts = mutableListOf(
        PermissionPrompt(
            permission = Manifest.permission.READ_CONTACTS,
            title = "Autorizzazione Contatti",
            message = "Serve per aggiungere i contatti SOS da avvisare."
        ),
        PermissionPrompt(
            permission = Manifest.permission.SEND_SMS,
            title = "Autorizzazione SMS",
            message = "Serve per inviare le richieste di aiuto ai contatti SOS."
        ),
        PermissionPrompt(
            permission = Manifest.permission.ACCESS_FINE_LOCATION,
            title = "Autorizzazione Localizzazione precisa",
            message = "Serve per inviare la tua posizione corretta nei messaggi di emergenza."
        ),
        PermissionPrompt(
            permission = Manifest.permission.CALL_PHONE,
            title = "Autorizzazione Telefono",
            message = "Serve per chiamare direttamente i numeri di emergenza."
        )
    )

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        prompts += PermissionPrompt(
            permission = Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            title = "Autorizzazione Localizzazione in background",
            message = "Serve per percorso sicuro e aiuto con scuotimento anche quando l'app non è aperta.",
            openInSettings = true
        )
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        prompts += PermissionPrompt(
            permission = Manifest.permission.POST_NOTIFICATIONS,
            title = "Autorizzazione Notifiche",
            message = "Serve per mostrare correttamente i servizi di emergenza attivi."
        )
    }

    return prompts
}

private fun appSettingsIntent(context: android.content.Context): Intent {
    return Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:${context.packageName}")
    }
}

