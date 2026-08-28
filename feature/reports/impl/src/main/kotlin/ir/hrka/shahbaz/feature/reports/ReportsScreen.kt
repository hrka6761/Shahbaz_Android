package ir.hrka.shahbaz.feature.reports

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shahbaz.flightblackbox.FbbReportDetails
import com.shahbaz.flightblackbox.FbbReportStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(
    onBack: () -> Unit,
    onOpenReportDetails: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReportsViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    BackHandler {
        if (state.selectionMode) {
            viewModel.clearSelection()
        } else {
            onBack()
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
                title = { Text("Reports") },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (state.selectionMode) {
                                viewModel.clearSelection()
                            } else {
                                onBack()
                            }
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
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
        ReportsList(
            state = state,
            contentPadding = padding,
            onOpenDetails = onOpenReportDetails,
            onEnterSelectionMode = viewModel::enterSelectionMode,
            onToggleSelected = viewModel::toggleReportSelection,
            onSelectAll = viewModel::setAllReportsSelected,
            onClearSelection = viewModel::clearSelection,
            onDeleteSelected = viewModel::deleteSelectedReports,
        )
    }
}

@Composable
private fun ReportsList(
    state: ReportsUiState,
    contentPadding: PaddingValues,
    onOpenDetails: (String) -> Unit,
    onEnterSelectionMode: (String) -> Unit,
    onToggleSelected: (String) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onClearSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
) {
    val selectableCount = state.reports.count { !it.isActiveReport }
    val selectedCount = state.selectedSessionIds.size
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        if (state.selectionMode) {
            SelectionActions(
                selectedCount = selectedCount,
                selectableCount = selectableCount,
                allSelected = selectableCount > 0 && selectedCount == selectableCount,
                onSelectAll = onSelectAll,
                onClearSelection = onClearSelection,
                onDeleteSelected = onDeleteSelected,
            )
        }
        if (state.busy && state.reports.isEmpty()) {
            LoadingReports()
        } else if (state.reports.isEmpty()) {
            EmptyReports()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = 8.dp,
                    end = 16.dp,
                    bottom = 16.dp,
                ),
            ) {
                items(state.reports, key = { it.descriptor.sessionId }) { report ->
                    ReportCard(
                        report = report,
                        selected = report.descriptor.sessionId in state.selectedSessionIds,
                        selectionMode = state.selectionMode,
                        onEnterSelectionMode = { onEnterSelectionMode(report.descriptor.sessionId) },
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
    selectableCount: Int,
    allSelected: Boolean,
    onSelectAll: (Boolean) -> Unit,
    onClearSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = allSelected,
                onCheckedChange = onSelectAll,
                enabled = selectableCount > 0,
            )
            Text("Select all")
            Text(
                "$selectedCount selected",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onClearSelection,
                modifier = Modifier.weight(1f),
            ) {
                Text("Clear")
            }
            Button(
                onClick = onDeleteSelected,
                enabled = selectedCount > 0,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Rounded.Delete, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Delete")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReportCard(
    report: FbbReportDetails,
    selected: Boolean,
    selectionMode: Boolean,
    onEnterSelectionMode: () -> Unit,
    onToggleSelected: () -> Unit,
    onOpenDetails: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (selectionMode) {
                        onToggleSelected()
                    } else {
                        onOpenDetails()
                    }
                },
                onLongClick = onEnterSelectionMode,
            ),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggleSelected() },
                    enabled = !report.isActiveReport,
                )
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ReportStatusIcon(report.descriptor.status)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        report.descriptor.fileName,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    formatTimestamp(report.descriptor.startedAtEpochMillis),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ReportInfoLine("Status", report.descriptor.status.name)
                    ReportInfoLine("Size", formatBytes(report.descriptor.sizeBytes))
                }
            }
        }
    }
}

@Composable
private fun ReportInfoLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LoadingReports() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyReports() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
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

private fun formatTimestamp(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(epochMillis))

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
