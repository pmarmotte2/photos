package fr.notedefrais.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import fr.notedefrais.app.data.DailySummary
import fr.notedefrais.app.data.ExpenseCategory
import fr.notedefrais.app.data.Receipt
import fr.notedefrais.app.data.Trip
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ExpenseTheme {
                ExpenseApp()
            }
        }
    }
}

private val Green = Color(0xFF1D6B52)
private val PaleGreen = Color(0xFFE3F3EB)
private val WarmBackground = Color(0xFFF8F9F4)
private val Orange = Color(0xFFE47B35)
private val Red = Color(0xFFB3261E)

@Composable
private fun ExpenseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            primary = Green,
            secondary = Orange,
            background = WarmBackground,
            surface = Color.White,
            error = Red
        ),
        content = content
    )
}

@Composable
private fun ExpenseApp(vm: ExpenseViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var selectedTripId by remember { mutableStateOf<String?>(null) }
    val selectedTrip = state.trips.firstOrNull { it.id == selectedTripId }

    if (selectedTrip == null) {
        TripsScreen(
            trips = state.trips,
            receipts = state.receipts,
            onTripClick = { selectedTripId = it.id },
            onCreateTrip = vm::createTrip
        )
    } else {
        TripScreen(
            trip = selectedTrip,
            receipts = state.receipts.filter { it.tripId == selectedTrip.id },
            summaries = vm.dailySummaries(selectedTrip),
            onBack = { selectedTripId = null },
            onAddReceipt = vm::addReceipt,
            onDeleteReceipt = vm::deleteReceipt,
            fileFor = vm::fileFor
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TripsScreen(
    trips: List<Trip>,
    receipts: List<Receipt>,
    onTripClick: (Trip) -> Unit,
    onCreateTrip: (String, LocalDate, LocalDate, BigDecimal) -> Result<Unit>
) {
    var showCreateDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = WarmBackground,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("MES FRAIS", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Green)
                        Text("Déplacements", fontWeight = FontWeight.SemiBold)
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreateDialog = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Nouveau déplacement") }
            )
        }
    ) { padding ->
        if (trips.isEmpty()) {
            EmptyTrips(
                modifier = Modifier.padding(padding),
                onCreate = { showCreateDialog = true }
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        "Vos justificatifs, rangés par mission.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                items(trips, key = { it.id }) { trip ->
                    val tripReceipts = receipts.filter { it.tripId == trip.id }
                    TripCard(trip, tripReceipts, onClick = { onTripClick(trip) })
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateTripDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, start, end, allowance ->
                onCreateTrip(name, start, end, allowance).onSuccess { showCreateDialog = false }
            }
        )
    }
}

@Composable
private fun EmptyTrips(modifier: Modifier = Modifier, onCreate: () -> Unit) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(color = PaleGreen, shape = CircleShape, modifier = Modifier.size(88.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.ReceiptLong, null, tint = Green, modifier = Modifier.size(42.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
        Text("Aucun déplacement", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Créez une mission pour commencer à classer vos factures et suivre vos plafonds.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onCreate) { Text("Créer mon premier déplacement") }
    }
}

@Composable
private fun TripCard(trip: Trip, receipts: List<Receipt>, onClick: () -> Unit) {
    val total = receipts.fold(BigDecimal.ZERO) { sum, receipt -> sum + receipt.amount }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = PaleGreen, shape = RoundedCornerShape(12.dp)) {
                    Icon(
                        Icons.Default.DirectionsCar, null, tint = Green,
                        modifier = Modifier.padding(10.dp).size(24.dp)
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(trip.name, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    Text(
                        trip.dateRangeLabel(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                }
                Text(total.euros(), color = Green, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = Color(0xFFE9ECE7))
            Spacer(Modifier.height(12.dp))
            Row {
                Text("${receipts.size} justificatif${if (receipts.size > 1) "s" else ""}", fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                Text("${trip.dailyMealAllowance.euros()} / jour repas", color = Green, fontSize = 13.sp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TripScreen(
    trip: Trip,
    receipts: List<Receipt>,
    summaries: List<DailySummary>,
    onBack: () -> Unit,
    onAddReceipt: (Uri, Trip, LocalDate, BigDecimal, ExpenseCategory, String) -> Result<Unit>,
    onDeleteReceipt: (Receipt) -> Unit,
    fileFor: (Receipt) -> File
) {
    val context = LocalContext.current
    var showSourceDialog by remember { mutableStateOf(false) }
    var pendingSource by remember { mutableStateOf<PendingSource?>(null) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    var receiptToDelete by remember { mutableStateOf<Receipt?>(null) }

    val documentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val mime = context.contentResolver.getType(uri).orEmpty()
            pendingSource = PendingSource(uri, mime.ifBlank { "image/jpeg" })
        }
    }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) cameraUri?.let { pendingSource = PendingSource(it, "image/jpeg") }
    }

    fun startCamera() {
        val directory = File(context.cacheDir, "camera").apply { mkdirs() }
        val file = File(directory, "capture_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        cameraUri = uri
        cameraLauncher.launch(uri)
    }

    Scaffold(
        containerColor = WarmBackground,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(trip.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(trip.dateRangeLabel(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showSourceDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Ajouter un justificatif")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                TripOverview(trip, summaries, receipts)
            }
            items(summaries, key = { it.date.toString() }) { summary ->
                DayCard(
                    summary = summary,
                    receipts = receipts.filter { it.date == summary.date },
                    onOpen = { receipt ->
                        openReceipt(context, receipt, fileFor(receipt))
                    },
                    onDelete = { receiptToDelete = it }
                )
            }
        }
    }

    if (showSourceDialog) {
        SourceDialog(
            onDismiss = { showSourceDialog = false },
            onCamera = {
                showSourceDialog = false
                startCamera()
            },
            onFile = {
                showSourceDialog = false
                documentLauncher.launch(arrayOf("application/pdf", "image/*"))
            }
        )
    }

    pendingSource?.let { source ->
        AddReceiptDialog(
            trip = trip,
            source = source,
            onDismiss = { pendingSource = null },
            onConfirm = { date, amount, category ->
                onAddReceipt(source.uri, trip, date, amount, category, source.mimeType)
                    .onSuccess {
                        pendingSource = null
                        Toast.makeText(context, "Justificatif enregistré", Toast.LENGTH_SHORT).show()
                    }
                    .onFailure {
                        Toast.makeText(context, it.message ?: "Enregistrement impossible", Toast.LENGTH_LONG).show()
                    }
            }
        )
    }

    receiptToDelete?.let { receipt ->
        AlertDialog(
            onDismissRequest = { receiptToDelete = null },
            title = { Text("Supprimer ce justificatif ?") },
            text = { Text("Le fichier sera supprimé définitivement de l’application.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteReceipt(receipt)
                    receiptToDelete = null
                }) { Text("Supprimer", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { receiptToDelete = null }) { Text("Annuler") }
            }
        )
    }
}

@Composable
private fun TripOverview(trip: Trip, summaries: List<DailySummary>, receipts: List<Receipt>) {
    val total = receipts.fold(BigDecimal.ZERO) { sum, receipt -> sum + receipt.amount }
    val meals = summaries.fold(BigDecimal.ZERO) { sum, day -> sum + day.mealSpent }
    val allowance = trip.dailyMealAllowance * summaries.size.toBigDecimal()
    Card(
        colors = CardDefaults.cardColors(containerColor = Green),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(22.dp)) {
            Text("VUE D’ENSEMBLE", color = Color.White.copy(alpha = .72f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(total.euros(), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("Total de tous les frais", color = Color.White.copy(alpha = .75f))
            Spacer(Modifier.height(18.dp))
            Row {
                OverviewStat("Repas", meals.euros(), Modifier.weight(1f))
                OverviewStat("Droits repas", allowance.euros(), Modifier.weight(1f))
                OverviewStat("Pièces", receipts.size.toString(), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun OverviewStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, color = Color.White.copy(alpha = .65f), fontSize = 12.sp)
        Text(value, color = Color.White, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DayCard(
    summary: DailySummary,
    receipts: List<Receipt>,
    onOpen: (Receipt) -> Unit,
    onDelete: (Receipt) -> Unit
) {
    val over = summary.remaining.signum() < 0
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(summary.date.fullDateLabel(), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    Text(
                        if (over) "Plafond dépassé de ${summary.remaining.abs().euros()}"
                        else "${summary.remaining.euros()} disponibles pour les repas",
                        color = if (over) Red else Green,
                        fontSize = 13.sp
                    )
                }
                Text(
                    "${summary.mealSpent.euros()} / ${summary.allowance.euros()}",
                    fontWeight = FontWeight.SemiBold,
                    color = if (over) Red else MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { summary.ratio },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = if (over) Red else Green,
                trackColor = PaleGreen
            )
            if (receipts.isEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("Aucun justificatif ce jour", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            } else {
                Spacer(Modifier.height(12.dp))
                receipts.forEachIndexed { index, receipt ->
                    if (index > 0) HorizontalDivider(color = Color(0xFFF0F1EE))
                    ReceiptRow(receipt, onOpen = { onOpen(receipt) }, onDelete = { onDelete(receipt) })
                }
            }
        }
    }
}

@Composable
private fun ReceiptRow(receipt: Receipt, onOpen: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(color = categoryColor(receipt.category), shape = RoundedCornerShape(10.dp)) {
            Icon(
                categoryIcon(receipt.category), null, tint = Green,
                modifier = Modifier.padding(9.dp).size(21.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(receipt.category.label, fontWeight = FontWeight.Medium)
            Text(
                receipt.storedFileName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(receipt.amount.euros(), fontWeight = FontWeight.SemiBold)
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.DeleteOutline, contentDescription = "Supprimer", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CreateTripDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, LocalDate, LocalDate, BigDecimal) -> Unit
) {
    val today = LocalDate.now()
    var name by remember { mutableStateOf("") }
    var start by remember { mutableStateOf(today.toString()) }
    var end by remember { mutableStateOf(today.toString()) }
    var allowance by remember { mutableStateOf("40,00") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nouveau déplacement") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Nom (ex. Les Clayes)") }, singleLine = true
                )
                OutlinedTextField(
                    value = start, onValueChange = { start = it },
                    label = { Text("Date de début (AAAA-MM-JJ)") }, singleLine = true
                )
                OutlinedTextField(
                    value = end, onValueChange = { end = it },
                    label = { Text("Date de fin (AAAA-MM-JJ)") }, singleLine = true
                )
                OutlinedTextField(
                    value = allowance, onValueChange = { allowance = it },
                    label = { Text("Plafond repas quotidien (€)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val parsedStart = start.toDateOrNull()
                val parsedEnd = end.toDateOrNull()
                val parsedAllowance = allowance.toMoneyOrNull()
                error = when {
                    name.isBlank() -> "Donnez un nom au déplacement."
                    parsedStart == null || parsedEnd == null -> "Utilisez le format AAAA-MM-JJ."
                    parsedEnd.isBefore(parsedStart) -> "La fin doit être après le début."
                    parsedAllowance == null || parsedAllowance.signum() < 0 -> "Le plafond est invalide."
                    else -> null
                }
                if (error == null) onConfirm(name, parsedStart!!, parsedEnd!!, parsedAllowance!!)
            }) { Text("Créer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

@Composable
private fun SourceDialog(onDismiss: () -> Unit, onCamera: () -> Unit, onFile: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.ReceiptLong, null) },
        title = { Text("Ajouter un justificatif") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onCamera, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.CameraAlt, null)
                    Spacer(Modifier.width(10.dp))
                    Text("Prendre une photo")
                }
                OutlinedButton(onClick = onFile, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.UploadFile, null)
                    Spacer(Modifier.width(10.dp))
                    Text("Importer PDF ou image")
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fermer") } }
    )
}

@Composable
private fun AddReceiptDialog(
    trip: Trip,
    source: PendingSource,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate, BigDecimal, ExpenseCategory) -> Unit
) {
    val defaultDate = LocalDate.now().coerceIn(trip.startDate, trip.endDate)
    var date by remember { mutableStateOf(defaultDate.toString()) }
    var amount by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(ExpenseCategory.MEAL) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Détails du justificatif") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (source.mimeType == "application/pdf") Icons.Default.Description else Icons.Default.InsertDriveFile,
                        null, tint = Green
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (source.mimeType == "application/pdf") "Document PDF" else "Image", color = Green)
                }
                OutlinedTextField(
                    value = amount, onValueChange = { amount = it },
                    label = { Text("Montant TTC (€)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                OutlinedTextField(
                    value = date, onValueChange = { date = it },
                    label = { Text("Date (AAAA-MM-JJ)") }, singleLine = true,
                    supportingText = { Text("Entre ${trip.startDate} et ${trip.endDate}") }
                )
                Text("Catégorie", fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ExpenseCategory.entries.forEach { option ->
                        FilterChip(
                            selected = category == option,
                            onClick = { category = option },
                            label = { Text(option.label, fontSize = 11.sp) }
                        )
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val parsedDate = date.toDateOrNull()
                val parsedAmount = amount.toMoneyOrNull()
                error = when {
                    parsedAmount == null || parsedAmount.signum() <= 0 -> "Saisissez un montant supérieur à 0."
                    parsedDate == null -> "La date est invalide."
                    parsedDate.isBefore(trip.startDate) || parsedDate.isAfter(trip.endDate) ->
                        "La date doit appartenir au déplacement."
                    else -> null
                }
                if (error == null) onConfirm(parsedDate!!, parsedAmount!!, category)
            }) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

private data class PendingSource(val uri: Uri, val mimeType: String)

private fun openReceipt(context: android.content.Context, receipt: Receipt, file: File) {
    if (!file.exists()) {
        Toast.makeText(context, "Le fichier n’existe plus.", Toast.LENGTH_SHORT).show()
        return
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, receipt.mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(intent) }
        .onFailure {
            Toast.makeText(context, "Aucune application ne peut ouvrir ce fichier.", Toast.LENGTH_LONG).show()
        }
}

private fun categoryIcon(category: ExpenseCategory): ImageVector = when (category) {
    ExpenseCategory.MEAL -> Icons.Default.Restaurant
    ExpenseCategory.TRANSPORT -> Icons.Default.DirectionsCar
    ExpenseCategory.HOTEL -> Icons.Default.Hotel
    ExpenseCategory.OTHER -> Icons.Default.MoreHoriz
}

private fun categoryColor(category: ExpenseCategory): Color = when (category) {
    ExpenseCategory.MEAL -> Color(0xFFE3F3EB)
    ExpenseCategory.TRANSPORT -> Color(0xFFE8EFFB)
    ExpenseCategory.HOTEL -> Color(0xFFF5EAF8)
    ExpenseCategory.OTHER -> Color(0xFFF3F0E8)
}

private fun BigDecimal.euros(): String =
    "${setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',')} €"

private fun String.toMoneyOrNull(): BigDecimal? =
    trim().replace(',', '.').toBigDecimalOrNull()?.setScale(2, RoundingMode.HALF_UP)

private fun String.toDateOrNull(): LocalDate? =
    try {
        LocalDate.parse(trim())
    } catch (_: DateTimeParseException) {
        null
    }

private fun Trip.dateRangeLabel(): String {
    val short = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.FRANCE)
    return "Du ${startDate.format(short)} au ${endDate.format(short)}"
}

private fun LocalDate.fullDateLabel(): String =
    format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRANCE))
        .replaceFirstChar { it.titlecase(Locale.FRANCE) }
