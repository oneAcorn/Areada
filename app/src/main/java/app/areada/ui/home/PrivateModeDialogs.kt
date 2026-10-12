package app.areada.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.areada.R
import app.areada.data.library.LibraryBookEntry
import app.areada.data.private.PrivatePasswordMinimumLength
import app.areada.data.private.PrivateModePassword
import app.areada.ui.reader.PromptChoiceButton

internal data class PrivateLibraryUiState(
    val enabled: Boolean = false,
    val passwordSet: Boolean = false,
    val busy: Boolean = false,
    val books: List<LibraryBookEntry> = emptyList(),
)

internal enum class PrivatePasswordPrompt {
    CREATE,
    UNLOCK,
    LOCK,
}

@Composable
internal fun PrivateModePasswordDialog(
    mode: PrivatePasswordPrompt,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    val title = when (mode) {
        PrivatePasswordPrompt.CREATE -> stringResource(R.string.private_mode_set_password_title)
        PrivatePasswordPrompt.UNLOCK -> stringResource(R.string.private_mode_unlock_title)
        PrivatePasswordPrompt.LOCK -> stringResource(R.string.private_mode_lock_title)
    }
    val submitLabel = when (mode) {
        PrivatePasswordPrompt.CREATE -> stringResource(R.string.save)
        PrivatePasswordPrompt.UNLOCK -> stringResource(R.string.private_mode_unlock_action)
        PrivatePasswordPrompt.LOCK -> stringResource(R.string.private_mode_lock_action)
    }
    val tooShortMessage = stringResource(R.string.private_mode_password_too_short)
    val mismatchMessage = stringResource(R.string.private_mode_password_mismatch)

    var password by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }
    var localError by rememberSaveable { mutableStateOf<String?>(null) }

    val visibleError = localError ?: errorMessage

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .widthIn(max = 420.dp),
            shape = RectangleShape,
            color = MaterialTheme.colorScheme.background,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 16.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { value ->
                        password = value
                        localError = null
                    },
                    singleLine = true,
                    isError = visibleError != null,
                    label = { Text(text = stringResource(R.string.private_mode_password_label)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (mode == PrivatePasswordPrompt.CREATE) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = { value ->
                            confirmPassword = value
                            localError = null
                        },
                        singleLine = true,
                        isError = visibleError != null,
                        label = { Text(text = stringResource(R.string.private_mode_password_confirm_label)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (visibleError != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = visibleError,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    PromptChoiceButton(
                        label = submitLabel,
                        highlighted = true,
                        enabled = password.isNotEmpty(),
                        onClick = {
                            when {
                                mode == PrivatePasswordPrompt.CREATE &&
                                    !PrivateModePassword.isAcceptable(password) -> localError = tooShortMessage

                                mode == PrivatePasswordPrompt.CREATE && password != confirmPassword ->
                                    localError = mismatchMessage

                                else -> onSubmit(password)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    PromptChoiceButton(
                        label = stringResource(R.string.cancel),
                        highlighted = false,
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.private_mode_password_hint, PrivatePasswordMinimumLength),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}