package com.hienlh.ppm.chat

import androidx.compose.runtime.compositionLocalOf
import com.hienlh.ppm.state.ChatViewMode

/**
 * The device's chat view mode (`useChatViewMode`): Thinking and Verbose keep every thinking block
 * open, Verbose every tool card too. A change opens or folds what is drawn, whatever was clicked.
 */
val LocalChatViewMode = compositionLocalOf { ChatViewMode.NORMAL }
