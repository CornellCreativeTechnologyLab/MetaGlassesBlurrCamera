/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// WearablesViewModel - DAT Registration & Device Session Orchestrator
//
// This ViewModel owns the app-wide DAT registration/device state that the rest of the
// CameraAccess sample (HomeScreen, NonStreamScreen, StreamScreen) reads and reacts to:
// - Registration lifecycle (Wearables.startRegistration/startUnregistration)
// - Available/active device tracking (Wearables.devices)
// - Navigation between the Home / NonStream / Stream screens (via isStreaming)
// - Shared error/update-required state surfaced by StreamViewModel

package com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class WearablesViewModel(application: Application) : AndroidViewModel(application) {

  companion object {
    private const val TAG = "CameraAccess:WearablesViewModel"
    private const val META_AI_PACKAGE = "com.facebook.arstudio.player"
  }

  val deviceSelector: DeviceSelector = AutoDeviceSelector()

  private val _uiState = MutableStateFlow(WearablesUiState())
  val uiState: StateFlow<WearablesUiState> = _uiState.asStateFlow()

  init {
    viewModelScope.launch {
      Wearables.registrationState.collect { state ->
        Log.d(TAG, "Registration state changed: $state")
        _uiState.update {
          it.copy(
              registrationState = state,
              canRegister = state == RegistrationState.AVAILABLE,
          )
        }
      }
    }
    viewModelScope.launch {
      Wearables.devices.collect { devices ->
        _uiState.update {
          it.copy(
              devices = devices.toPersistentList(),
              hasActiveDevice = devices.isNotEmpty(),
          )
        }
      }
    }
  }

  fun onPermissionsResult(result: Map<String, Boolean>, onAllGranted: () -> Unit) {
    if (result.values.all { it }) {
      onAllGranted()
    } else {
      setRecentError("Required app permissions were not granted")
    }
  }

  fun startRegistration(activity: Activity) {
    Wearables.startRegistration(activity)
  }

  fun startUnregistration(activity: Activity) {
    Wearables.startUnregistration(activity)
  }

  // The DAT SDK does not expose a direct "open firmware update" API; the update flow is
  // handled inside the Meta AI app itself. Best-effort: bring the user to Meta AI and clear
  // the flag once they've had a chance to act on it there.
  fun openFirmwareUpdate(activity: Activity) {
    launchMetaAi(activity)
  }

  fun openDATGlassesAppUpdate(activity: Activity) {
    launchMetaAi(activity)
  }

  private fun launchMetaAi(activity: Activity) {
    val launchIntent = activity.packageManager.getLaunchIntentForPackage(META_AI_PACKAGE)
    if (launchIntent != null) {
      activity.startActivity(launchIntent)
    } else {
      val marketIntent =
          Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$META_AI_PACKAGE"))
      activity.startActivity(marketIntent)
    }
  }

  fun navigateToStreaming(onRequestWearablesPermission: suspend (Permission) -> PermissionStatus) {
    viewModelScope.launch {
      val status = onRequestWearablesPermission(Permission.CAMERA)
      if (status == PermissionStatus.Granted) {
        _uiState.update { it.copy(isStreaming = true) }
      } else {
        setRecentError("Camera permission was not granted")
      }
    }
  }

  fun navigateToDeviceSelection() {
    _uiState.update { it.copy(isStreaming = false) }
  }

  fun setDatAppUpdateRequired(required: Boolean) {
    _uiState.update { it.copy(isDatAppUpdateRequired = required) }
  }

  fun setRecentError(message: String) {
    _uiState.update { it.copy(recentError = message) }
  }

  fun clearRecentError() {
    _uiState.update { it.copy(recentError = null) }
  }

  fun showDebugMenu() {
    _uiState.update { it.copy(isDebugMenuVisible = true) }
  }

  fun hideDebugMenu() {
    _uiState.update { it.copy(isDebugMenuVisible = false) }
  }

  fun hideGettingStartedSheet() {
    _uiState.update { it.copy(isGettingStartedSheetVisible = false) }
  }
}
