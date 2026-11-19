package com.presentationglass.app

import android.util.Log
import com.meta.wearable.dat.inputs.types.InputEvent
import com.meta.wearable.dat.inputs.types.NavDirection

/**
 * Turns Neural Band and touchpad gestures into slide actions on the PC.
 *
 * - A right swipe goes to the next slide, a left swipe to the previous one. With the phone
 *   connected as a Bluetooth keyboard, these are real arrow keys, so any slide software reacts.
 *   Without it, the Chrome extension moves the slides.
 * - A pinch plays or pauses the videos on the current slide.
 *
 * Up and down swipes do nothing. Mouse control and counted pinches were tried and dropped: the
 * Neural Band does not deliver quick double and triple pinches reliably.
 */
class GestureMapper {

  private companion object {
    const val TAG = "GestureMapper"
  }

  fun onInput(event: InputEvent) {
    Log.i(TAG, "Input: $event")
    when (event) {
      is InputEvent.Nav ->
          when (event.direction) {
            NavDirection.RIGHT -> Presenter.requestNavigation(NavigationAction.NEXT)
            NavDirection.LEFT -> Presenter.requestNavigation(NavigationAction.PREV)
            else -> {}
          }
      is InputEvent.Select -> Presenter.requestPlaybackToggle()
      else -> {}
    }
  }
}
