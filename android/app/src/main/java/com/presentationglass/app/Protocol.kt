package com.presentationglass.app

/**
 * Names used in the messages between the PC and the phone, so that each one is spelled in one
 * place only. The Chrome extension has the same list in chrome-extension/lib/protocol.js. The
 * messages are described in docs/protocol.md.
 */
object Protocol {
  const val VERSION = 3
  const val PORT = 8765

  object Type {
    const val HELLO = "hello"
    const val WELCOME = "welcome"
    const val ERROR = "error"
    const val DECK = "deck"
    const val SLIDE = "slide"
    const val SLIDE_IMAGE = "slide-image"
    const val STATUS = "status"
    const val RESET_TIMER = "reset-timer"
    const val NAVIGATE = "navigate"
    const val PLAYBACK = "playback"
    const val PING = "ping"
    const val PONG = "pong"
  }

  object Level {
    const val OK = "ok"
    const val WARN = "warn"
  }
}

/** A request to move the slides, sent from the phone to the PC. */
enum class NavigationAction(val wireName: String) {
  NEXT("next"),
  PREV("prev"),
}
