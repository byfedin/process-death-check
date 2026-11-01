package dev.byfedin.pdcheck.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Broken: reads Session.user, which only MainActivity sets. Restoring this screen crashes. */
class SessionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val user = Session.user
        setContent {
            MaterialTheme {
                Text("Signed in as $user", Modifier.fillMaxSize().padding(24.dp))
            }
        }
    }
}
