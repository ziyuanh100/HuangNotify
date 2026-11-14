package dev.huang.notify;

/** App-owned messages safe to display without exposing provider paths or notification text. */
final class BackupProblem extends IllegalArgumentException {
  final BackupFiles.Failure kind;
  BackupProblem(String message){this(message,BackupFiles.Failure.OTHER);}
  BackupProblem(String message,BackupFiles.Failure kind){super(message);this.kind=kind;}
}
