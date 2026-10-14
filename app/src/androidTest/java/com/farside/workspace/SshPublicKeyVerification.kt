package com.farside.workspace

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.farside.workspace.data.deriveSshPublicKey
import com.farside.workspace.data.ProfileManager

class SshPublicKeyVerification(private val instrumentation: Instrumentation) {
    private val automation get() = instrumentation.uiAutomation
    private fun nodes(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
        listOf(node) + (0 until node.childCount).flatMap { node.getChild(it)?.let(::nodes).orEmpty() }
    private fun find(label: String): AccessibilityNodeInfo {
        repeat(50) {
            automation.clearCache()
            val windows = automation.windowsOnAllDisplays
            val all = (0 until windows.size()).flatMap { windows.valueAt(it).flatMap { window -> window.root?.let(::nodes).orEmpty() } }
            all.firstOrNull { it.text?.toString() == label || it.contentDescription?.toString() == label || it.hintText?.toString() == label }?.let { return it }
            all.filter { it.isScrollable }.forEach { it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
            Thread.sleep(200)
        }
        error("Missing UI control: $label")
    }
    private fun click(label: String) {
        var node = find(label)
        while (!node.isClickable) node = checkNotNull(node.parent)
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        Thread.sleep(300)
    }
    private fun enter(label: String, value: String) {
        var node = find(label)
        while (!node.isEditable) node = checkNotNull(node.parent)
        check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
    }
    fun run(): String {
        val assets = instrumentation.context.assets
        for ((file, passphrase) in listOf("rsa2" to "", "ed2" to "fixture-pass")) {
            val path = "ssh-public/$file"
            val privateKey = assets.open(path).bufferedReader().use { it.readText() }
            val expected = assets.open("$path.expected").bufferedReader().use { it.readText().trim() }
            val fingerprint = assets.open("$path.fingerprint").bufferedReader().use { it.readText().trim() }
            val actual = deriveSshPublicKey(privateKey, passphrase, "fixture-$file")
            check(actual.line == expected) { "$file public line mismatch" }
            check(actual.fingerprint == fingerprint) { "$file fingerprint mismatch" }
        }
        try {
            deriveSshPublicKey("invalid PEM", "", "fixture")
            error("Malformed PEM accepted")
        } catch (_: LocalizableException) { }
        try {
            val privateKey = assets.open("ssh-public/ed2").bufferedReader().use { it.readText() }
            deriveSshPublicKey(privateKey, "wrong", "fixture")
            error("Wrong passphrase accepted")
        } catch (_: LocalizableException) { }
        val context = instrumentation.targetContext
        val manager = ProfileManager(context)
        val name = "fixture-rsa2"
        manager.getSshKeys().filter { it.name == name }.forEach { manager.deleteSshKey(it.id) }
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        try {
            click("Settings")
            click("SSH Keys")
            enter("Key Display Name", name)
            enter("Private Key", assets.open("ssh-public/rsa2").bufferedReader().use { it.readText() })
            click("Save SSH Key")
            find(name)
            click("Show public key for $name")
            find("Fingerprint: " + assets.open("ssh-public/rsa2.fingerprint").bufferedReader().use { it.readText().trim() })
            find(assets.open("ssh-public/rsa2.expected").bufferedReader().use { it.readText().trim() })
            click("Copy")
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            check(clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ==
                assets.open("ssh-public/rsa2.expected").bufferedReader().use { it.readText().trim() })
        } finally {
            activity.finish()
            manager.getSshKeys().filter { it.name == name }.forEach { manager.deleteSshKey(it.id) }
        }
        return "PASS: ssh-keygen RSA and encrypted Ed25519 lines/fingerprints, malformed PEM and wrong passphrase; Settings import, public dialog, clipboard, fixture deletion.\n"
    }
}
