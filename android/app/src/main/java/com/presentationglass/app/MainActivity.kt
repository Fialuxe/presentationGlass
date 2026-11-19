package com.presentationglass.app

import android.Manifest.permission.BLUETOOTH
import android.Manifest.permission.BLUETOOTH_ADVERTISE
import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.INTERNET
import android.Manifest.permission.POST_NOTIFICATIONS
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.RegistrationState

class MainActivity : ComponentActivity() {

  private companion object {
    val REQUIRED_PERMISSIONS = listOf(BLUETOOTH, BLUETOOTH_CONNECT, BLUETOOTH_ADVERTISE, INTERNET)
  }

  // POST_NOTIFICATIONS exists from Android 13. Without it, the service notification is hidden,
  // but the service still runs.
  private val permissions =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        (REQUIRED_PERMISSIONS + POST_NOTIFICATIONS).toTypedArray()
      } else {
        REQUIRED_PERMISSIONS.toTypedArray()
      }
  private val wearablesReady = mutableStateOf(false)

  private val permissionsLauncher =
      registerForActivityResult(RequestMultiplePermissions()) { results ->
        // Notifications are optional; the other permissions are required.
        val required = results.filterKeys { it in REQUIRED_PERMISSIONS }
        if (required.values.all { it }) {
          if (!wearablesReady.value) {
            Wearables.initialize(this)
            wearablesReady.value = true
          }
          PresenterService.start(this)
        } else {
          Toast.makeText(this, R.string.bluetooth_permission_required, Toast.LENGTH_LONG).show()
        }
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      MaterialTheme {
        if (wearablesReady.value) {
          HomeScreen(activity = this)
        } else {
          Text(stringResource(R.string.checking_permissions), modifier = Modifier.safeDrawingPadding().padding(24.dp))
        }
      }
    }
  }

  override fun onStart() {
    super.onStart()
    permissionsLauncher.launch(permissions)
    Presenter.refreshAddresses()
    Presenter.hid.refresh()
  }
}

@Composable
private fun HomeScreen(activity: ComponentActivity) {
  val state by Presenter.state.collectAsState()
  val registration by
      Wearables.registrationState.collectAsState(initial = RegistrationState.UNAVAILABLE)

  Column(
      modifier =
          Modifier.fillMaxSize()
              .safeDrawingPadding()
              .verticalScroll(rememberScrollState())
              .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Text(stringResource(R.string.app_name), fontSize = 24.sp, fontWeight = FontWeight.Bold)

    Section(title = stringResource(R.string.section_register)) {
      Text(stringResource(R.string.registration_state, stringResource(registrationLabel(registration))))
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { Wearables.startRegistration(activity) },
            enabled = registration != RegistrationState.REGISTERED,
        ) {
          Text(stringResource(R.string.register_button))
        }
        OutlinedButton(
            onClick = { Wearables.startUnregistration(activity) },
            enabled = registration == RegistrationState.REGISTERED,
        ) {
          Text(stringResource(R.string.unregister_button))
        }
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(
            checked = state.usePhonePreview,
            onCheckedChange = { on ->
              Presenter.glasses.stop()
              if (!on) Presenter.glasses.disableMockGlasses()
              Presenter.setPhonePreview(on)
            },
        )
        Text(stringResource(R.string.phone_preview_switch), modifier = Modifier.padding(start = 8.dp))
      }
      state.chromePreviewUrl?.let { url ->
        Text(
            stringResource(R.string.phone_preview_hint, url),
            style = MaterialTheme.typography.bodySmall,
        )
      }
    }

    Section(title = stringResource(R.string.section_keyboard)) {
      val hidStatus by Presenter.hid.status.collectAsState()
      Text(stringResource(R.string.keyboard_explanation))
      Text(
          stringResource(
              R.string.keyboard_status,
              stringResource(
                  when (hidStatus) {
                    HidController.Status.UNAVAILABLE -> R.string.keyboard_unavailable
                    HidController.Status.REGISTERING -> R.string.keyboard_registering
                    HidController.Status.WAITING_FOR_PC -> R.string.keyboard_waiting
                    HidController.Status.CONNECTED -> R.string.keyboard_connected
                  }
              ),
          ),
          fontWeight = FontWeight.Bold,
      )
      if (hidStatus != HidController.Status.CONNECTED) {
        Text(stringResource(R.string.keyboard_pairing_steps), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(
            onClick = {
              activity.startActivity(
                  Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                      .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
              )
            }
        ) {
          Text(stringResource(R.string.keyboard_discoverable_button))
        }
      }
    }

    Section(title = stringResource(R.string.section_pc)) {
      Text(stringResource(R.string.pc_instructions))
      Text(stringResource(R.string.pin_label, state.pin), fontSize = 28.sp, fontWeight = FontWeight.Bold)
      Text(stringResource(R.string.port_label, state.serverPort))
      if (state.addresses.isEmpty()) {
        Text(stringResource(R.string.no_ip_address))
      } else {
        state.addresses.forEach { Text(stringResource(R.string.ip_label, it)) }
      }
      Text(stringResource(if (state.pcConnected) R.string.pc_connected else R.string.pc_disconnected), fontWeight = FontWeight.Bold)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { Presenter.refreshAddresses() }) { Text(stringResource(R.string.refresh_ip_button)) }
        OutlinedButton(onClick = { Presenter.regeneratePin() }) { Text(stringResource(R.string.change_pin_button)) }
      }
    }

    Section(title = stringResource(R.string.section_glasses)) {
      Text(stringResource(R.string.glasses_state, stringResource(glassesLabel(state.glassesStatus))))
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { Presenter.glasses.start() },
            enabled =
                state.glassesStatus == GlassesStatus.IDLE ||
                    state.glassesStatus == GlassesStatus.ERROR,
        ) {
          Text(stringResource(R.string.start_display_button))
        }
        OutlinedButton(onClick = { Presenter.glasses.stop() }) { Text(stringResource(R.string.stop_display_button)) }
      }
      Text(stringResource(R.string.display_pattern_label), fontWeight = FontWeight.Bold)
      listOf(
              DisplayPattern.TWO_SLIDES to R.string.display_pattern_two_slides,
              DisplayPattern.FOCUS to R.string.display_pattern_focus,
          )
          .forEach { (pattern, label) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier.fillMaxWidth().selectable(
                        selected = state.displayPattern == pattern,
                        onClick = { Presenter.setDisplayPattern(pattern) },
                        role = Role.RadioButton,
                    ),
            ) {
              RadioButton(selected = state.displayPattern == pattern, onClick = null)
              Text(stringResource(label), modifier = Modifier.padding(start = 8.dp))
            }
          }
      Text(stringResource(R.string.glasses_controls_hint), style = MaterialTheme.typography.bodySmall)
      state.lastTiming?.let { timing ->
        Text(
            stringResource(
                R.string.render_timing,
                timing.stage.name,
                timing.bytes / 1024f,
                timing.composeMs.toInt(),
                timing.sendMs.toInt(),
            )
        )
        timing.fromInputMs?.let { Text(stringResource(R.string.render_timing_from_input, it.toInt())) }
      }
    }

    Section(title = stringResource(R.string.section_current_slide)) {
      Text(
          if (state.slideCount == 0) stringResource(R.string.no_deck_yet)
          else
              stringResource(
                  R.string.deck_position,
                  state.deckTitle,
                  state.currentIndex + 1,
                  state.slideCount,
              )
      )
      state.slideImages[state.currentIndex]?.let { bitmap ->
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = state.slideTitles[state.currentIndex],
            modifier = Modifier.fillMaxWidth(),
        )
      }
      state.slideImages[state.currentIndex + 1]?.let { bitmap ->
        Text(stringResource(R.string.phone_next_slide))
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = state.slideTitles[state.currentIndex + 1],
            modifier = Modifier.fillMaxWidth(0.5f),
        )
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { Presenter.requestNavigation(NavigationAction.PREV) }) {
          Text(stringResource(R.string.prev_button))
        }
        Button(onClick = { Presenter.requestNavigation(NavigationAction.NEXT) }) {
          Text(stringResource(R.string.next_button))
        }
      }
    }

    state.lastMessage?.let {
      Text(it, style = MaterialTheme.typography.bodySmall)
    }

    OutlinedButton(
        onClick = {
          Presenter.glasses.stop()
          PresenterService.stop(activity)
          activity.finish()
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
      Text(stringResource(R.string.quit_button))
    }
  }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
      content()
    }
  }
}

@StringRes
private fun registrationLabel(state: RegistrationState): Int =
    when (state) {
      RegistrationState.REGISTERED -> R.string.registration_registered
      RegistrationState.AVAILABLE -> R.string.registration_available
      else -> R.string.registration_unavailable
    }

@StringRes
private fun glassesLabel(status: GlassesStatus): Int =
    when (status) {
      GlassesStatus.IDLE -> R.string.glasses_idle
      GlassesStatus.CONNECTING -> R.string.glasses_connecting
      GlassesStatus.READY -> R.string.glasses_ready
      GlassesStatus.ERROR -> R.string.glasses_error
    }
