package com.rimpydroid.manager.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.AssistChip as MaterialAssistChip
import androidx.compose.material3.Button as MaterialButton
import androidx.compose.material3.FilterChip as MaterialFilterChip
import androidx.compose.material3.OutlinedButton as MaterialOutlinedButton
import androidx.compose.material3.OutlinedTextField as MaterialOutlinedTextField

// The controls of the app in the Liquid glass style. These have the names and the parameters of Material's own, and
// the files of this package use them instead (they do not import Material's). In the classic style, and wherever the
// lens is not available, each one is exactly the Material control it wraps. With the lens, the container is a small
// pane of glass: the same refraction and rim as the cards, with the accent as the colour of the glass for the
// primary button and for a chip that is selected.

/** A filled button. With the lens: a pane of glass in the colour of the button. */
@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    if (!rememberRealGlass()) {
        MaterialButton(onClick, modifier, enabled, shape, colors, contentPadding = contentPadding, content = content)
        return
    }
    val dark = LocalDarkTheme.current
    val body = if (enabled) colors.containerColor else colors.disabledContainerColor
    MaterialButton(
        onClick = onClick,
        modifier = modifier.glassBackground(shape, dark, tint = body, kind = GlassKind.CONTROL),
        enabled = enabled,
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = colors.contentColor,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = colors.disabledContentColor,
        ),
        contentPadding = contentPadding,
        content = content,
    )
}

/** An outlined button. With the lens: a clear pane of glass; its rim is the outline. */
@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.outlinedShape,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    if (!rememberRealGlass()) {
        MaterialOutlinedButton(onClick, modifier, enabled, shape, colors, contentPadding = contentPadding, content = content)
        return
    }
    val dark = LocalDarkTheme.current
    MaterialOutlinedButton(
        onClick = onClick,
        modifier = modifier.glassBackground(shape, dark, kind = GlassKind.CONTROL),
        enabled = enabled,
        shape = shape,
        colors = colors,
        border = null,
        contentPadding = contentPadding,
        content = content,
    )
}

/** A selectable chip. With the lens: clear glass; selected, the glass takes the colour of the accent. */
@Composable
fun FilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    if (!rememberRealGlass()) {
        MaterialFilterChip(
            selected = selected,
            onClick = onClick,
            label = label,
            modifier = modifier,
            enabled = enabled,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
        )
        return
    }
    val dark = LocalDarkTheme.current
    val shape = FilterChipDefaults.shape
    val accent = MaterialTheme.colorScheme.primary
    MaterialFilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        modifier = modifier.glassBackground(
            shape, dark, tint = if (selected) accent.copy(alpha = 0.34f) else Color.Transparent, kind = GlassKind.CONTROL,
        ),
        enabled = enabled,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        shape = shape,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent,
            selectedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            disabledSelectedContainerColor = Color.Transparent,
        ),
        border = null,
    )
}

/** A chip that does something. With the lens: clear glass. */
@Composable
fun AssistChip(
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    if (!rememberRealGlass()) {
        MaterialAssistChip(
            onClick = onClick,
            label = label,
            modifier = modifier,
            enabled = enabled,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
        )
        return
    }
    val dark = LocalDarkTheme.current
    val shape = AssistChipDefaults.shape
    MaterialAssistChip(
        onClick = onClick,
        label = label,
        modifier = modifier.glassBackground(shape, dark, kind = GlassKind.CONTROL),
        enabled = enabled,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        shape = shape,
        colors = AssistChipDefaults.assistChipColors(containerColor = Color.Transparent, disabledContainerColor = Color.Transparent),
        border = null,
    )
}

/**
 * A text field. With the lens (and no floating label, which would stick out of the box): a pane of glass behind the
 * text; the outline is then drawn only while the field has focus.
 */
@Composable
fun OutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
) {
    if (label != null || !rememberRealGlass()) {
        MaterialOutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            enabled = enabled,
            readOnly = readOnly,
            textStyle = textStyle,
            label = label,
            placeholder = placeholder,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            isError = isError,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            singleLine = singleLine,
            maxLines = maxLines,
            minLines = minLines,
        )
        return
    }
    val dark = LocalDarkTheme.current
    val shape = OutlinedTextFieldDefaults.shape
    MaterialOutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.glassBackground(shape, dark, kind = GlassKind.CONTROL),
        enabled = enabled,
        readOnly = readOnly,
        textStyle = textStyle,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        isError = isError,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        shape = shape,
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = Color.Transparent,
            disabledBorderColor = Color.Transparent,
        ),
    )
}
