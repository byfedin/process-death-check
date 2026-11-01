package dev.byfedin.pdcheck.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Correct: state is kept with rememberSaveable and survives process death. */
class SavedFormActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val note = rememberSaveable { mutableStateOf("") }
            val urgent = rememberSaveable { mutableStateOf(false) }
            Form("Saved form", note, urgent)
        }
    }
}

/** Broken: plain remember survives rotation tests in some setups but not process death. */
class ForgetfulFormActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val note = remember { mutableStateOf("") }
            val urgent = remember { mutableStateOf(false) }
            Form("Forgetful form", note, urgent)
        }
    }
}

@Composable
private fun Form(title: String, note: MutableState<String>, urgent: MutableState<Boolean>) {
    var text by note
    var checked by urgent
    MaterialTheme {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Note") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                Modifier.fillMaxWidth()
                    .toggleable(value = checked, role = Role.Switch) { checked = it },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Urgent", Modifier.weight(1f))
                Switch(checked = checked, onCheckedChange = null)
            }
        }
    }
}
