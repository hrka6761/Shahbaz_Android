package ir.hrka.shahbaz.feature.reportdetails

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shahbaz.flightblackbox.FbbReportDetails
import com.shahbaz.flightblackbox.FbbReportStatus
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportDetailsScreen(
    sessionId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReportDetailsViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var route by rememberSaveable(sessionId) { mutableStateOf(ReportDetailsRoute.DETAILS.name) }
    var pendingExportSessionId by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { destination: Uri? ->
        val pendingSessionId = pendingExportSessionId
        pendingExportSessionId = null
        if (destination == null || pendingSessionId == null) return@rememberLauncherForActivityResult
        val report = viewModel.reportFile(pendingSessionId)
        if (report == null) {
            viewModel.showMessage("Report file is no longer available")
            return@rememberLauncherForActivityResult
        }
        runCatching { exportReport(context, report, destination) }
            .onSuccess { viewModel.showMessage("Report exported") }
            .onFailure { viewModel.showMessage("Report export failed") }
    }

    LaunchedEffect(sessionId) {
        viewModel.loadReportDetails(sessionId)
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearMessage()
    }

    BackHandler {
        if (route == ReportDetailsRoute.VIEWER.name) {
            route = ReportDetailsRoute.DETAILS.name
        } else {
            onBack()
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(reportDetailsTitle(route)) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (route == ReportDetailsRoute.VIEWER.name) {
                                route = ReportDetailsRoute.DETAILS.name
                            } else {
                                onBack()
                            }
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        when (route) {
            ReportDetailsRoute.VIEWER.name -> ReportViewer(
                state = state,
                contentPadding = padding,
                onSearch = viewModel::searchReport,
                onLoadMore = viewModel::loadMoreReportText,
                onCopy = {
                    viewModel.showMessage("Loaded report text copied")
                },
            )

            else -> ReportDetails(
                state = state,
                contentPadding = padding,
                onViewReport = {
                    route = ReportDetailsRoute.VIEWER.name
                    viewModel.openReport(sessionId)
                },
                onShare = {
                    val report = viewModel.reportFile(sessionId)
                    if (report == null) {
                        viewModel.showMessage("Report file is no longer available")
                    } else {
                        runCatching { shareReport(context, report) }
                            .onFailure { viewModel.showMessage("Report share failed") }
                    }
                },
                onExport = {
                    val report = viewModel.reportFile(sessionId)
                    if (report == null) {
                        viewModel.showMessage("Report file is no longer available")
                    } else {
                        pendingExportSessionId = sessionId
                        exportLauncher.launch(report.name)
                    }
                },
                onDelete = { viewModel.deleteReport(sessionId) },
            )
        }
    }
}

@Composable
private fun ReportDetails(
    state: ReportDetailsUiState,
    contentPadding: PaddingValues,
    onViewReport: () -> Unit,
    onShare: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val report = state.report
    if (report == null) {
        MissingReport(contentPadding)
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
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
        }
        HorizontalDivider()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onViewReport,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Description, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("View")
            }
            OutlinedButton(
                onClick = onShare,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Share, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Share")
            }
            OutlinedButton(
                onClick = onExport,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Download, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Export")
            }
            OutlinedButton(
                onClick = onDelete,
                enabled = !report.isActiveReport,
                modifier = Modifier.fillMaxWidth(),
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
    state: ReportDetailsUiState,
    contentPadding: PaddingValues,
    onSearch: (String) -> Unit,
    onLoadMore: () -> Unit,
    onCopy: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
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

private fun reportDetailsTitle(route: String): String = when (route) {
    ReportDetailsRoute.VIEWER.name -> "Report Viewer"
    else -> "Report Details"
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

private enum class ReportDetailsRoute {
    DETAILS,
    VIEWER,
}
