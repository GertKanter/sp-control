package sp.unification

import sp.modelSupport._
import sp.devicehandler._
import sp.domain.Logic._
import sp.drivers.{ROSFlatStateDriver, URDriver}

class Dummy extends ModelDSL {
  // state
  dv("currentPos", "driver","currentPos")
  dv("hasTool", "driver", "hasTool")

  // cmd
  dv("active", "driver", "active")
  dv("refPos", "driver", "refPos")
  v("refPos", "init", List("init", "failed"))
  v("currentPos", "init", List("stop", "stopFailed"))

  // turtle abilities
  a("moveForward", List(),
    c("pre", "true", "refPos := 10", "active := true"),
    c("started", "currentPos != refPos"),
    c("post", "currentPos == refPos"),
    c("reset", "true"))

  a("moveBackward", List(),
    c("pre", "true", "refPos := 0", "active := true"),
    c("started", "currentPos != refPos"),
    c("post", "currentPos == refPos"),
    c("reset", "true"))

  // turtle operations
  o("moveForward")(
    c("pre", "currentPos == 0"),
    c("reset", "true")
  )

  o("moveBackward")(
    c("pre", "currentPos == 10"),
    c("reset", "true")
  )

  resource("resource") // blank list of things = take everything
}

class DummyExample extends ModelDSL {
  use("DummyRB", new Dummy)

  runner("turtlerunner")

  driver("driver", URDriver.driverType)

}

object DummyExample {
  def apply() = new DummyExample
}
