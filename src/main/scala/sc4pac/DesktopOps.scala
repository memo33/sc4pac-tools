package io.github.memo33
package sc4pac
package cli

import zio.{Task, ZIO}

object DesktopOps {

  def openUrl(url: java.net.URI): Task[Unit] = ZIO.attempt {
    import java.awt.Desktop
    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
      Desktop.getDesktop().browse(url)
    } else throw new UnsupportedOperationException("Desktop operations are not supported on this platform.")
  }

  def openDirectory(directory: os.Path): Task[Unit] = ZIO.attempt {
    import java.awt.Desktop
    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
      Desktop.getDesktop().open(directory.toIO)
    } else throw new UnsupportedOperationException("Desktop operations are not supported on this platform.")
  }

}
