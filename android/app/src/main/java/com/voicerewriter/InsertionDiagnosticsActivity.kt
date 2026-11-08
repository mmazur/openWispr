package com.voicerewriter

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class InsertionDiagnosticsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            com.voicerewriter.ui.OpenWisprTheme {
                Surface { DiagnosticsScreen() }
            }
        }
    }
}

@Composable
private fun DiagnosticsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(InsertionDiagnostics.enabled(context)) }
    var reports by remember { mutableStateOf(emptyList<InsertionReport>()) }
    var selectedPackage by rememberSaveable { mutableStateOf<String?>(null) }
    var exportPackage by rememberSaveable { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<InsertionReport?>(null) }
    var clearConfirm by remember { mutableStateOf(false) }

    fun refresh() = scope.launch {
        reports = withContext(Dispatchers.IO) { InsertionDiagnostics.reports(context) }
    }
    LaunchedEffect(Unit) { refresh() }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val selected = InsertionDiagnostics.reports(context).filter {
                        exportPackage == null || it.appPackage == exportPackage
                    }
                    val stream = context.contentResolver.openOutputStream(uri, "wt")
                        ?: error("Couldn't open the chosen file")
                    stream.bufferedWriter(Charsets.UTF_8).use { writer ->
                        writer.write("OpenWispr insertion diagnostics\nNo dictated text, field contents or clipboard contents are included.\n\n")
                        selected.forEach { writer.write(it.display() + "\n\n---\n\n") }
                    }
                }
            }
            Toast.makeText(context, if (result.isSuccess) "Diagnostics saved" else "Couldn't save diagnostics", Toast.LENGTH_LONG).show()
        }
    }

    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("Insertion diagnostics", style = MaterialTheme.typography.headlineSmall)
            Text("Stored only on this phone. Up to 2 MiB total and 100 reports across all apps; oldest reports are deleted automatically. " +
                "No dictated text, field contents or clipboard contents are saved.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Record insertion problems", Modifier.weight(1f))
                Switch(enabled, onCheckedChange = {
                    enabled = it
                    InsertionDiagnostics.setEnabled(context, it)
                })
            }
            Text("Includes direct insertion failures even when paste succeeds, and accepted actions whose result could not be verified.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            Row {
                TextButton(onClick = { refresh() }) { Text("Refresh") }
                TextButton(onClick = {
                    exportPackage = selectedPackage
                    exporter.launch("OpenWispr-insertion-${selectedPackage ?: "all-apps"}.txt")
                }, enabled = reports.isNotEmpty()) { Text("Export") }
                TextButton(onClick = { clearConfirm = true }, enabled = reports.isNotEmpty()) { Text("Clear") }
            }
        }
        if (selectedPackage == null) {
            if (reports.isEmpty()) item { Text("No failed insertions recorded yet.") }
            items(reports.groupBy { it.appPackage }.entries.toList(), key = { it.key }) { group ->
                val label = remember(group.key) {
                    runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(group.key, 0)).toString() }
                        .getOrDefault(group.key)
                }
                Column(Modifier.fillMaxWidth().clickable { selectedPackage = group.key }.padding(vertical = 8.dp)) {
                    Text(label, style = MaterialTheme.typography.titleMedium)
                    Text("${group.key} · ${group.value.size} reports", style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            item {
                TextButton(onClick = { selectedPackage = null }) { Text("All apps") }
                Text(selectedPackage.orEmpty(), style = MaterialTheme.typography.titleMedium)
            }
            items(reports.filter { it.appPackage == selectedPackage }, key = { it.id }) { report ->
                Column(Modifier.fillMaxWidth().clickable { detail = report }.padding(vertical = 8.dp)) {
                    Text(java.util.Date(report.timestamp).toString())
                    Text(report.outcome, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    detail?.let { report ->
        AlertDialog(onDismissRequest = { detail = null }, title = { Text("Insertion report") },
            text = {
                SelectionContainer {
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        Text(report.display(), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
            }, confirmButton = { TextButton(onClick = { detail = null }) { Text("Close") } })
    }
    if (clearConfirm) AlertDialog(onDismissRequest = { clearConfirm = false }, title = { Text("Clear diagnostics?") },
        text = { Text(if (selectedPackage == null) "Delete all saved insertion reports?" else "Delete reports for $selectedPackage?") },
        confirmButton = {
            TextButton(onClick = {
                clearConfirm = false
                scope.launch {
                    withContext(Dispatchers.IO) { InsertionDiagnostics.clear(context, selectedPackage) }
                    refresh()
                }
            }) { Text("Clear") }
        }, dismissButton = { TextButton(onClick = { clearConfirm = false }) { Text("Cancel") } })
}
