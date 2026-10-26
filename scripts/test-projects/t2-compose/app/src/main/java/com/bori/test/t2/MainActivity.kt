package com.bori.test.t2

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Counter() } }
    }
}

@Composable
fun Counter() {
    var count by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { Log.i("BORI_TEST", "t2 OK composed") }
    Column(Modifier.padding(24.dp)) {
        Text("Compose count: $count", style = MaterialTheme.typography.headlineMedium)
        Button(onClick = { count++ }) { Text("Add one") }
    }
}
