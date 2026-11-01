package dev.byfedin.pdcheck.sample

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Each screen below is correct or broken on purpose, so pdcheck has something to find. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Bug pattern: global state initialised by the first screen only.
        Session.user = "demo"
        setContent {
            MaterialTheme {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("pdcheck sample", style = MaterialTheme.typography.headlineSmall)
                    ScreenButton("Saved form", SavedFormActivity::class.java)
                    ScreenButton("Forgetful form", ForgetfulFormActivity::class.java)
                    ScreenButton("Session screen", SessionActivity::class.java)
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun ScreenButton(label: String, screen: Class<out Activity>) {
        Button(onClick = { startActivity(Intent(this, screen)) }) { Text(label) }
    }
}
