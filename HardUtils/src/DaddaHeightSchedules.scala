package HardUtils

object DaddaHeightScheduleCarrySave {
  def apply(maxInputHeight: Int, targetHeight: Int = 2): List[Int] = {
    require(targetHeight >= 2)
    require(targetHeight <= maxInputHeight)

    var (stageLimits, nextLimit) = (List.empty[Int], targetHeight)
    while (nextLimit < maxInputHeight) {
      stageLimits = nextLimit :: stageLimits
      nextLimit = (nextLimit * 3) >> 1
    }
    stageLimits
  }
}

object DaddaHeightScheduleCarryChain {
  def apply(maxInputHeight: Int, targetHeight: Int = 2): List[Int] = {
    require(targetHeight >= 2)
    require(targetHeight <= maxInputHeight)

    if (maxInputHeight <= 4) {
      DaddaHeightScheduleCarrySave(maxInputHeight, targetHeight)
    } else {
      var (stageLimits, nextLimit) = if (targetHeight < 4) {
        (DaddaHeightScheduleCarrySave(4, targetHeight), 4)
      } else {
        (List.empty[Int], targetHeight)
      }
      while (nextLimit < maxInputHeight) {
        stageLimits = nextLimit :: stageLimits
        nextLimit = nextLimit << 1
      }
      stageLimits
    }
  }
}
