package HardUtils

object DaddaHeightScheduleCarrySave {
  def apply(initialHeight: Int): List[Int] = {
    var stageLimits: List[Int] = List.empty
    var nextLimit:   Int = 2
    while (nextLimit < initialHeight) {
      stageLimits = nextLimit :: stageLimits
      nextLimit = (nextLimit * 3) >> 1
    }
    stageLimits
  }
}

object DaddaHeightScheduleCarryChain {
  def apply(initialHeight: Int): List[Int] = {
    if (initialHeight <= 4) {
      DaddaHeightScheduleCarrySave(initialHeight)
    } else {
      var stageLimits: List[Int] = DaddaHeightScheduleCarrySave(4)
      var nextLimit:   Int = 4
      while (nextLimit < initialHeight) {
        stageLimits = nextLimit :: stageLimits
        nextLimit = nextLimit << 1
      }
      stageLimits
    }
  }
}
