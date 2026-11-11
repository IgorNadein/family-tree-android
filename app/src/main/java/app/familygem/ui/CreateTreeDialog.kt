package app.familygem.ui

import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import app.familygem.R

/**
 * Adds a Compose dialog to a legacy Activity without replacing its ActivityResult
 * launchers and existing import/backup actions.
 */
fun ComponentActivity.showCreateTreeDialog(onCreate: (String) -> Unit) {
    val container = findViewById<ViewGroup>(android.R.id.content)
    val host = ComposeView(this).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }
    fun dismiss() {
        container.removeView(host)
    }
    host.setContent {
        FamilyTreeTheme {
            CreateTreeDialog(
                onDismiss = ::dismiss,
                onCreate = { title ->
                    dismiss()
                    onCreate(title)
                }
            )
        }
    }
    container.addView(
        host,
        ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    )
}

@Composable
private fun CreateTreeDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.modify_later),
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onCreate(title.trim()) })
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(title.trim()) }) {
                Text(stringResource(R.string.create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
