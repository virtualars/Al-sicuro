package it.alsicuro.virtualars

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import it.alsicuro.virtualars.ui.HelpMeApp
import it.alsicuro.virtualars.ui.HelpMeViewModel

// Activity minimale: crea il ViewModel applicativo
// e consegna il controllo al tree Compose.
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: HelpMeViewModel = viewModel(factory = HelpMeViewModel.Factory(application))
            HelpMeApp(vm)
        }
    }
}
