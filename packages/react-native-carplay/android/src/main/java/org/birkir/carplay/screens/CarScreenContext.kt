package org.birkir.carplay.screens

import org.birkir.carplay.utils.EventEmitter

data class CarScreenContext(
  val screenMarker: String,
  var eventEmitter: EventEmitter,
  var screens: HashMap<String, CarScreen>
)
