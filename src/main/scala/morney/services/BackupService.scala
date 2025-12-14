package morney.services

import zio.{Task, ZIO}

import java.nio.file.{Files, Path, StandardCopyOption}

final class BackupService {
  def backupDb(dbPath: Path, backupPath: Path): Task[Path] =
    ZIO.attemptBlocking {
      val parent = backupPath.getParent
      if (parent != null) Files.createDirectories(parent)
      Files.copy(dbPath, backupPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
      backupPath
    }

  def restoreDb(backupPath: Path, dbPath: Path): Task[Path] =
    ZIO.attemptBlocking {
      val parent = dbPath.getParent
      if (parent != null) Files.createDirectories(parent)
      Files.copy(backupPath, dbPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
      dbPath
    }
}
