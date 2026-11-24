
package com.simmy.assistant

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ChatGPTAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ChatGPTAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // SIMMY will use this service to interact with ChatGPT.
    }

    override fun onInterrupt() {
        // Required by Android.
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    fun sendMessage(message: String): Boolean {
        val root = rootInActiveWindow ?: return false

        val input = findInputField(root) ?: return false

        val arguments = android.os.Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                message
            )
        }

        val textSet = input.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            arguments
        )

        if (!textSet) return false

        val sendButton = findSendButton(root) ?: return false

        return sendButton.performAction(
            AccessibilityNodeInfo.ACTION_CLICK
        )
    }

    fun readVisibleText(): String {
        val root = rootInActiveWindow ?: return ""
        val collectedText = mutableListOf<String>()

        collectText(root, collectedText)

        return collectedText
            .distinct()
            .joinToString("\n")
            .trim()
    }

    private fun findInputField(
        node: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {

        if (node.isEditable) return node

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findInputField(child)
            if (result != null) return result
        }

        return null
    }

    private fun findSendButton(
        node: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {

        val description = node.contentDescription
            ?.toString()
            ?.lowercase()
            ?: ""

        val text = node.text
            ?.toString()
            ?.lowercase()
            ?: ""

        if (
            node.isClickable &&
            (
                description.contains("send") ||
                text == "send" ||
                description.contains("submit")
            )
        ) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findSendButton(child)
            if (result != null) return result
        }

        return null
    }

    private fun collectText(
        node: AccessibilityNodeInfo,
        output: MutableList<String>
    ) {
        val text = node.text?.toString()?.trim()

        if (!text.isNullOrBlank() && !node.isEditable) {
            output.add(text)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectText(child, output)
        }
    }
}
