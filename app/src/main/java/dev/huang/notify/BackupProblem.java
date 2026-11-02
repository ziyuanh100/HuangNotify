package dev.huang.notify;

/** App-owned messages safe to display without exposing provider paths or notification text. */
final class BackupProblem extends IllegalArgumentException {
  BackupProblem(String message){super(message);}
}
