package com.greenart7c3.nip46tester

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.greenart7c3.nip46tester.ui.screens.BunkerScreen
import com.greenart7c3.nip46tester.ui.screens.ClientScreen
import com.greenart7c3.nip46tester.ui.screens.RelayTestScreen
import com.greenart7c3.nip46tester.ui.theme.Nip46TesterTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Nip46TesterTheme { App() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    var selected by remember { mutableIntStateOf(0) }
    val tabs = listOf("Client", "Bunker", "Relay test")
    Scaffold(
        topBar = { TopAppBar(title = { Text("NIP-46 Tester") }) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                PrimaryTabRow(selectedTabIndex = selected) {
                    tabs.forEachIndexed { i, title ->
                        Tab(
                            selected = selected == i,
                            onClick = { selected = i },
                            text = { Text(title) },
                        )
                    }
                }
                when (selected) {
                    0 -> ClientScreen()
                    1 -> BunkerScreen()
                    2 -> RelayTestScreen()
                }
            }
        }
    }
}
