package com.telugu.prompter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

sealed interface Screen {
    data object List : Screen
    data class Edit(val script: Script) : Screen
    data class Prompt(val script: Script) : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = ScriptStore(this)
        val remotePage = assets.open("remote.html").bufferedReader().use { it.readText() }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFFFC94D))) {
                var screen by remember { mutableStateOf<Screen>(Screen.List) }
                var scripts by remember { mutableStateOf(store.loadScripts()) }
                var settings by remember { mutableStateOf(store.loadSettings()) }
                var showRemote by remember { mutableStateOf(false) }
                val remote = remember { RemoteHub(store, remotePage) { assets.open("remote-tls.p12") } }

                DisposableEffect(remote) { onDispose { remote.stop() } }

                // A script sent from the laptop opens straight away.
                LaunchedEffect(remote) {
                    remote.commands.collect { c ->
                        if (c is RemoteCommand.OpenScript) {
                            scripts = store.loadScripts()
                            scripts.find { it.id == c.id }?.let { screen = Screen.Prompt(it) }
                        }
                    }
                }

                BackHandler(enabled = screen != Screen.List) { screen = Screen.List }

                when (val s = screen) {
                    Screen.List -> ScriptListScreen(
                        scripts = scripts,
                        remoteOn = remote.isOn,
                        onRemote = { showRemote = true },
                        onOpen = { screen = Screen.Prompt(it) },
                        onEdit = { screen = Screen.Edit(it) },
                        onNew = { screen = Screen.Edit(Script(ScriptStore.newId(), "", "")) },
                        onDelete = { store.delete(it.id); scripts = store.loadScripts() },
                    )

                    is Screen.Edit -> EditorScreen(
                        script = s.script,
                        onSave = {
                            store.upsert(it)
                            scripts = store.loadScripts()
                            screen = Screen.List
                        },
                        onCancel = { screen = Screen.List },
                    )

                    is Screen.Prompt -> key(s.script.id, s.script.body) {
                        PrompterScreen(
                            script = s.script,
                            settings = settings,
                            onSettingsChange = { settings = it; store.saveSettings(it) },
                            remote = remote,
                            onRemote = { showRemote = true },
                            onBack = { screen = Screen.List },
                        )
                    }
                }

                if (showRemote) RemoteDialog(remote, onDismiss = { showRemote = false })
            }
        }
    }
}
