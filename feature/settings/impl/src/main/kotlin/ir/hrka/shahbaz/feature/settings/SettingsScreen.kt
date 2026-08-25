package ir.hrka.shahbaz.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shahbaz.flightblackbox.FbbConfig
import com.shahbaz.flightblackbox.FbbDurabilityMode
import com.shahbaz.flightblackbox.FbbReportDetails
import com.shahbaz.flightblackbox.FbbReportStatus
import com.shahbaz.flightblackbox.FbbTraceLevel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var route by rememberSaveable { mutableStateOf(SettingsRoute.HOME.name) }
    var routeSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingExportSessionId by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { destination: Uri? ->
        val sessionId = pendingExportSessionId
        pendingExportSessionId = null
        if (destination == null || sessionId == null) return@rememberLauncherForActivityResult
        val report = viewModel.reportFile(sessionId)
        if (report == null) {
            viewModel.showMessage("Report file is no longer available")
            return@rememberLauncherForActivityResult
        }
        runCatching { exportReport(context, report, destination) }
            .onSuccess { viewModel.showMessage("Report exported") }
            .onFailure { viewModel.showMessage("Report export failed") }
    }

    BackHandler {
        if (route == SettingsRoute.HOME.name) {
            onBack()
        } else {
            route = SettingsRoute.HOME.name
            routeSessionId = null
        }
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearMessage()
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(settingsTitle(route)) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (route == SettingsRoute.HOME.name) {
                                onBack()
                            } else {
                                route = SettingsRoute.HOME.name
                                routeSessionId = null
                            }
                        }
                    ) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh reports")
                    }
                },
            )
        },
    ) { padding ->
        when (route) {
            SettingsRoute.REPORTS.name -> ReportsManager(
                state = state,
                contentPadding = padding,
                onOpenDetails = { sessionId ->
                    routeSessionId = sessionId
                    route = SettingsRoute.DETAILS.name
                },
                onToggleSelected = viewModel::toggleReportSelection,
                onClearSelection = viewModel::clearSelection,
                onDeleteSelected = viewModel::deleteSelectedReports,
                onDeleteAll = viewModel::deleteAllReports,
                onCleanupSevenDays = { viewModel.cleanupOlderThan(days = 7) },
                onCleanupStorage = { viewModel.cleanupToMaxStorageBytes(maxBytes = 50L * 1024L * 1024L) },
            )

            SettingsRoute.DETAILS.name -> ReportDetails(
                state = state,
                sessionId = routeSessionId,
                contentPadding = padding,
                onViewReport = { sessionId ->
                    routeSessionId = sessionId
                    route = SettingsRoute.VIEWER.name
                    viewModel.openReport(sessionId)
                },
                onShare = { sessionId ->
                    val report = viewModel.reportFile(sessionId)
                    if (report == null) {
                        viewModel.showMessage("Report file is no longer available")
                    } else {
                        runCatching { shareReport(context, report) }
                            .onFailure { viewModel.showMessage("Report share failed") }
                    }
                },
                onExport = { sessionId ->
                    val report = viewModel.reportFile(sessionId)
                    if (report == null) {
                        viewModel.showMessage("Report file is no longer available")
                    } else {
                        pendingExportSessionId = sessionId
                        exportLauncher.launch(report.name)
                    }
                },
                onDelete = viewModel::deleteReport,
            )

            SettingsRoute.VIEWER.name -> ReportViewer(
                state = state,
                sessionId = routeSessionId,
                contentPadding = padding,
                onSearch = viewModel::searchReport,
                onLoadMore = viewModel::loadMoreReportText,
                onCopy = {
                    viewModel.showMessage("Loaded report text copied")
                },
            )

            else -> SettingsHome(
                state = state,
                contentPadding = padding,
                onOpenReports = { route = SettingsRoute.REPORTS.name },
                onTraceLevel = viewModel::setTraceLevel,
                onDurabilityMode = viewModel::setDurabilityMode,
                onResetConfig = viewModel::resetConfiguration,
                onCleanupSevenDays = { viewModel.cleanupOlderThan(days = 7) },
                onCleanupThirtyDays = { viewModel.cleanupOlderThan(days = 30) },
                onCleanupStorage = { viewModel.cleanupToMaxStorageBytes(maxBytes = 50L * 1024L * 1024L) },
            )
        }
    }
}

@Composable
private fun SettingsHome(
    state: SettingsUiState,
    contentPadding: PaddingValues,
    onOpenReports: () -> Unit,
    onTraceLevel: (FbbTraceLevel) -> Unit,
    onDurabilityMode: (FbbDurabilityMode) -> Unit,
    onResetConfig: () -> Unit,
    onCleanupSevenDays: () -> Unit,
    onCleanupThirtyDays: () -> Unit,
    onCleanupStorage: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SummaryPanel(state = state, onOpenReports = onOpenReports)
        ConfigurationPanel(
            config = state.config,
            onTraceLevel = onTraceLevel,
            onDurabilityMode = onDurabilityMode,
            onResetConfig = onResetConfig,
        )
        CleanupPanel(
            onCleanupSevenDays = onCleanupSevenDays,
            onCleanupThirtyDays = onCleanupThirtyDays,
            onCleanupStorage = onCleanupStorage,
        )
    }
}

@Composable
private fun SummaryPanel(state: SettingsUiState, onOpenReports: () -> Unit) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Storage, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Flight Black Box", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${state.stats.reportCount} reports, ${formatBytes(state.stats.totalBytes)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(label = "${state.stats.activeReportCount} active")
                StatusPill(label = "${state.reports.sumOf { it.errorCount }} errors")
                StatusPill(label = "${state.reports.sumOf { it.warningCount }} warnings")
            }
            Button(onClick = onOpenReports, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Description, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Manage Reports")
            }
        }
    }
}

@Composable
private fun ConfigurationPanel(
    config: FbbConfig,
    onTraceLevel: (FbbTraceLevel) -> Unit,
    onDurabilityMode: (FbbDurabilityMode) -> Unit,
    onResetConfig: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Tune, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Recorder Configuration", style = MaterialTheme.typography.titleMedium)
                    Text("Saved changes apply on next app launch", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text("Trace Level", style = MaterialTheme.typography.labelLarge)
            EnumChips(
                values = FbbTraceLevel.entries,
                selected = config.traceLevel,
                label = { it.name.lowercase().replaceFirstChar(Char::uppercase) },
                onSelected = onTraceLevel,
            )
            Text("Durability Mode", style = MaterialTheme.typography.labelLarge)
            EnumChips(
                values = FbbDurabilityMode.entries,
                selected = config.durabilityMode,
                label = { it.name.lowercase().replaceFirstChar(Char::uppercase) },
                onSelected = onDurabilityMode,
            )
            OutlinedButton(onClick = onResetConfig) {
                Icon(Icons.Rounded.Restore, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Reset Recorder Defaults")
            }
        }
    }
}

@Composable
private fun CleanupPanel(
    onCleanupSevenDays: () -> Unit,
    onCleanupThirtyDays: () -> Unit,
    onCleanupStorage: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.DeleteSweep, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text("Cleanup", style = MaterialTheme.typography.titleMedium)
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onCleanupSevenDays) {
                    Text("Older Than 7 Days")
                }
                OutlinedButton(onClick = onCleanupThirtyDays) {
                    Text("Older Than 30 Days")
                }
                OutlinedButton(onClick = onCleanupStorage) {
                    Text("Keep Under 50 MB")
                }
            }
        }
    }
}

@Composable
private fun ReportsManager(
    state: SettingsUiState,
    contentPadding: PaddingValues,
    onOpenDetails: (String) -> Unit,
    onToggleSelected: (String) -> Unit,
    onClearSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
    onDeleteAll: () -> Unit,
    onCleanupSevenDays: () -> Unit,
    onCleanupStorage: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
    ) {
        if (state.selectedSessionIds.isNotEmpty()) {
            SelectionActions(
                selectedCount = state.selectedSessionIds.size,
                onClearSelection = onClearSelection,
                onDeleteSelected = onDeleteSelected,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onCleanupSevenDays) {
                Icon(Icons.Rounded.DeleteSweep, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Old Cleanup")
            }
            OutlinedButton(onClick = onCleanupStorage) {
                Icon(Icons.Rounded.Storage, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Storage Cleanup")
            }
            OutlinedButton(onClick = onDeleteAll) {
                Icon(Icons.Rounded.Delete, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Delete Deletable")
            }
        }
        if (state.reports.isEmpty()) {
            EmptyReports()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(state.reports, key = { it.descriptor.sessionId }) { report ->
                    ReportRow(
                        report = report,
                        selected = report.descriptor.sessionId in state.selectedSessionIds,
                        onToggleSelected = { onToggleSelected(report.descriptor.sessionId) },
                        onOpenDetails = { onOpenDetails(report.descriptor.sessionId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectionActions(
    selectedCount: Int,
    onClearSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$selectedCount selected", modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onClearSelection) {
            Text("Clear")
        }
        Button(onClick = onDeleteSelected) {
            Icon(Icons.Rounded.Delete, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Delete")
        }
    }
}

@Composable
private fun ReportRow(
    report: FbbReportDetails,
    selected: Boolean,
    onToggleSelected: () -> Unit,
    onOpenDetails: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenDetails),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggleSelected() },
                enabled = !report.isActiveReport,
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ReportStatusIcon(report.descriptor.status)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        report.descriptor.fileName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    formatTimestamp(report.descriptor.startedAtEpochMillis),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusPill(label = report.descriptor.status.name)
                    StatusPill(label = "${report.eventCount} events")
                    StatusPill(label = formatBytes(report.descriptor.sizeBytes))
                }
            }
        }
    }
}

@Composable
private fun ReportDetails(
    state: SettingsUiState,
    sessionId: String?,
    contentPadding: PaddingValues,
    onViewReport: (String) -> Unit,
    onShare: (String) -> Unit,
    onExport: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val report = state.reports.firstOrNull { it.descriptor.sessionId == sessionId }
    if (report == null) {
        MissingReport(contentPadding)
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ReportHeader(report)
        DetailLine("Session", report.descriptor.sessionId)
        DetailLine("Started", formatTimestamp(report.descriptor.startedAtEpochMillis))
        DetailLine("Ended", report.endedAtEpochMillis?.let(::formatTimestamp) ?: "Active")
        DetailLine("Duration", report.durationMillis?.let(::formatDuration) ?: "No events")
        DetailLine("Events", report.eventCount.toString())
        DetailLine("Warnings", report.warningCount.toString())
        DetailLine("Errors", report.errorCount.toString())
        DetailLine("Size", formatBytes(report.descriptor.sizeBytes))
        HorizontalDivider()
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = { onViewReport(report.descriptor.sessionId) }) {
                Icon(Icons.Rounded.Description, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("View")
            }
            OutlinedButton(onClick = { onShare(report.descriptor.sessionId) }) {
                Icon(Icons.Rounded.Share, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Share")
            }
            OutlinedButton(onClick = { onExport(report.descriptor.sessionId) }) {
                Icon(Icons.Rounded.Download, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Export")
            }
            OutlinedButton(
                onClick = { onDelete(report.descriptor.sessionId) },
                enabled = !report.isActiveReport,
            ) {
                Icon(Icons.Rounded.Delete, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Delete")
            }
        }
    }
}

@Composable
private fun ReportViewer(
    state: SettingsUiState,
    sessionId: String?,
    contentPadding: PaddingValues,
    onSearch: (String) -> Unit,
    onLoadMore: () -> Unit,
    onCopy: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    if (sessionId == null) {
        MissingReport(contentPadding)
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = onSearch,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Search loaded report") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            singleLine = true,
        )
        if (state.searchMatches.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                state.searchMatches.take(8).forEach { match ->
                    Text(
                        "Line ${match.lineNumber}: ${match.excerpt}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    clipboard.setText(AnnotatedString(state.loadedReportText))
                    onCopy()
                },
                enabled = state.loadedReportText.isNotEmpty(),
            ) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Copy Loaded")
            }
            Button(
                onClick = onLoadMore,
                enabled = state.nextReportOffsetBytes != null,
            ) {
                Text("Load More")
            }
        }
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            SelectionContainer {
                Text(
                    text = state.loadedReportText.ifBlank { "No report text loaded" },
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun ReportHeader(report: FbbReportDetails) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ReportStatusIcon(report.descriptor.status)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(report.descriptor.status.name, style = MaterialTheme.typography.titleMedium)
                Text(report.descriptor.fileName, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MissingReport(contentPadding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text("Report is no longer available")
    }
}

@Composable
private fun EmptyReports() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("No Flight Black Box reports yet")
    }
}

@Composable
private fun ReportStatusIcon(status: FbbReportStatus) {
    val icon = when (status) {
        FbbReportStatus.ACTIVE,
        FbbReportStatus.COMPLETED -> Icons.Rounded.CheckCircle
        FbbReportStatus.CRASHED,
        FbbReportStatus.ABNORMAL_TERMINATION,
        FbbReportStatus.INCOMPLETE -> Icons.Rounded.ErrorOutline
    }
    val tint = when (status) {
        FbbReportStatus.ACTIVE,
        FbbReportStatus.COMPLETED -> MaterialTheme.colorScheme.primary
        FbbReportStatus.CRASHED,
        FbbReportStatus.ABNORMAL_TERMINATION,
        FbbReportStatus.INCOMPLETE -> MaterialTheme.colorScheme.error
    }
    Icon(icon, contentDescription = null, tint = tint)
}

@Composable
private fun StatusPill(label: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun <T> EnumChips(
    values: List<T>,
    selected: T,
    label: (T) -> String,
    onSelected: (T) -> Unit,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        values.forEach { value ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelected(value) },
                label = { Text(label(value)) },
            )
        }
    }
}

private fun settingsTitle(route: String): String = when (route) {
    SettingsRoute.REPORTS.name -> "Reports"
    SettingsRoute.DETAILS.name -> "Report Details"
    SettingsRoute.VIEWER.name -> "Report Viewer"
    else -> "Settings"
}

private fun shareReport(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.flightblackbox.fileprovider",
        file,
    )
    val intent = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, file.name)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "Share flight black box report"))
}

private fun exportReport(context: Context, source: File, destination: Uri) {
    val output = context.contentResolver.openOutputStream(destination)
        ?: error("Could not open destination")
    output.use { destinationStream ->
        source.inputStream().use { sourceStream ->
            sourceStream.copyTo(destinationStream)
        }
    }
}

private fun formatTimestamp(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(epochMillis))

private fun formatDuration(durationMillis: Long): String {
    val totalSeconds = durationMillis / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return when {
        hours > 0L -> "${hours}h ${minutes}m ${seconds}s"
        minutes > 0L -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1_024L) return "$bytes B"
    val units = listOf("KB", "MB", "GB")
    var value = bytes / 1_024.0
    var index = 0
    while (value >= 1_024.0 && index < units.lastIndex) {
        value /= 1_024.0
        index += 1
    }
    return String.format(Locale.US, "%.1f %s", value, units[index])
}

private enum class SettingsRoute {
    HOME,
    REPORTS,
    DETAILS,
    VIEWER,
}
