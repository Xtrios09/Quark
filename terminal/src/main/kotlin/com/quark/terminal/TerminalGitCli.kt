package com.quark.terminal

import com.quark.git.DiffLineKind
import com.quark.git.GitChangeType
import com.quark.git.PureGitRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Executes `git ...` commands inside [TerminalSession] using [PureGitRepository]
 * with ANSI colorized output matching standard Git CLI conventions.
 */
object TerminalGitCli {

  private const val RESET = "\u001B[0m"
  private const val RED = "\u001B[31m"
  private const val GREEN = "\u001B[32m"
  private const val YELLOW = "\u001B[33m"
  private const val CYAN = "\u001B[36m"
  private const val BOLD = "\u001B[1m"
  private const val DIM = "\u001B[2m"

  private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

  suspend fun execute(
    args: List<String>,
    workTree: File,
    authorName: String,
    authorEmail: String,
    out: (String) -> Unit,
    onMutated: () -> Unit,
  ): Int {
    val repo = PureGitRepository(workTree)
    if (args.isEmpty() || args[0] == "help" || args[0] == "--help" || args[0] == "-h") {
      printHelp(out)
      return 0
    }

    return when (val sub = args[0]) {
      "init" -> {
        val branch = args.getOrNull(1)?.takeUnless { it.startsWith("-") } ?: "main"
        val res = repo.init(branch)
        res.fold(
          onSuccess = { msg ->
            out("$GREEN$msg$RESET\r\n")
            onMutated()
            0
          },
          onFailure = { e ->
            out("${RED}fatal: ${e.message}$RESET\r\n")
            128
          },
        )
      }

      "status" -> {
        if (!repo.isRepository()) {
          out("${RED}fatal: not a git repository (or any of the parent directories): .git$RESET\r\n")
          return 128
        }
        val snap = repo.snapshot()
        out("On branch $BOLD$CYAN${snap.currentBranch}$RESET\r\n")
        if (snap.headSha == null) {
          out("${DIM}No commits yet$RESET\r\n")
        }
        if (snap.stagedChanges.isEmpty() && snap.unstagedChanges.isEmpty()) {
          out("nothing to commit, working tree clean\r\n")
          return 0
        }
        if (snap.stagedChanges.isNotEmpty()) {
          out("\r\nChanges to be committed:\r\n")
          for (c in snap.stagedChanges) {
            val label = when (c.type) {
              GitChangeType.ADDED -> "new file:"
              GitChangeType.MODIFIED -> "modified:"
              GitChangeType.DELETED -> "deleted: "
              GitChangeType.UNTRACKED -> "added:   "
            }
            out("\t$GREEN$label   ${c.path}$RESET\r\n")
          }
        }
        val trackedMod = snap.unstagedChanges.filter { it.type != GitChangeType.UNTRACKED }
        val untracked = snap.unstagedChanges.filter { it.type == GitChangeType.UNTRACKED }
        if (trackedMod.isNotEmpty()) {
          out("\r\nChanges not staged for commit:\r\n")
          for (c in trackedMod) {
            val label = if (c.type == GitChangeType.DELETED) "deleted: " else "modified:"
            out("\t$RED$label   ${c.path}$RESET\r\n")
          }
        }
        if (untracked.isNotEmpty()) {
          out("\r\nUntracked files:\r\n")
          for (c in untracked) {
            out("\t$RED${c.path}$RESET\r\n")
          }
        }
        0
      }

      "add" -> {
        val specs = args.drop(1)
        if (specs.isEmpty()) {
          out("${RED}Nothing specified, nothing added. Try 'git add .'$RESET\r\n")
          return 1
        }
        var exit = 0
        for (spec in specs) {
          val res = repo.add(spec)
          res.onFailure { e ->
            out("${RED}fatal: ${e.message}$RESET\r\n")
            exit = 128
          }
        }
        onMutated()
        exit
      }

      "reset" -> {
        val target = args.drop(1).firstOrNull { !it.startsWith("-") }
        if (target == null) {
          val snap = repo.snapshot()
          for (c in snap.stagedChanges) {
            repo.unstage(c.path)
          }
          out("Unstaged ${snap.stagedChanges.size} file(s).\r\n")
        } else {
          repo.unstage(target).onFailure {
            out("${RED}error: ${it.message}$RESET\r\n")
            return 1
          }
        }
        onMutated()
        0
      }

      "commit" -> {
        val msgIdx = args.indexOf("-m")
        val msg = if (msgIdx >= 0 && msgIdx + 1 < args.size) args[msgIdx + 1] else ""
        if (msg.isBlank()) {
          out("${RED}Aborting commit due to empty commit message. Usage: git commit -m \"message\"$RESET\r\n")
          return 1
        }
        val res = repo.commit(msg, authorName, authorEmail)
        res.fold(
          onSuccess = { commit ->
            val snap = repo.snapshot()
            out("[$BOLD${snap.currentBranch}$RESET ${commit.shortSha}] ${commit.message}\r\n")
            onMutated()
            0
          },
          onFailure = { e ->
            out("${RED}error: ${e.message}$RESET\r\n")
            1
          },
        )
      }

      "log" -> {
        val nFlag = args.indexOf("-n")
        val limit = if (nFlag >= 0 && nFlag + 1 < args.size) args[nFlag + 1].toIntOrNull() ?: 25 else 25
        val commits = repo.log(limit)
        if (commits.isEmpty()) {
          out("${RED}fatal: current branch does not have any commits yet$RESET\r\n")
          return 128
        }
        for (c in commits) {
          val whenStr = dateFormat.format(Date(c.timestampSec * 1000L))
          out("${YELLOW}commit ${c.sha}$RESET\r\n")
          out("Author: ${c.authorName} <${c.authorEmail}>\r\n")
          out("Date:   $whenStr\r\n\r\n")
          out("    ${c.message}\r\n\r\n")
        }
        0
      }

      "diff" -> {
        val staged = args.contains("--staged") || args.contains("--cached")
        val path = args.drop(1).firstOrNull { !it.startsWith("-") }
        val diffs = repo.diff(path, staged)
        if (diffs.isEmpty()) {
          return 0
        }
        for (d in diffs) {
          for (line in d.lines) {
            val colored = when (line.kind) {
              DiffLineKind.HEADER -> "$CYAN${line.text}$RESET"
              DiffLineKind.ADDED -> "$GREEN+${line.text}$RESET"
              DiffLineKind.REMOVED -> "$RED-${line.text}$RESET"
              DiffLineKind.CONTEXT -> " ${line.text}"
            }
            out("$colored\r\n")
          }
        }
        0
      }

      "branch" -> {
        if (!repo.isRepository()) {
          out("${RED}fatal: not a git repository$RESET\r\n")
          return 128
        }
        val nameArg = args.drop(1).firstOrNull { !it.startsWith("-") }
        if (nameArg == null) {
          val snap = repo.snapshot()
          for (b in snap.branches) {
            if (b.isCurrent) out("* $GREEN${b.name}$RESET\r\n")
            else out("  ${b.name}\r\n")
          }
          0
        } else {
          repo.createBranch(nameArg, checkoutAfter = false).fold(
            onSuccess = {
              onMutated()
              0
            },
            onFailure = { e ->
              out("${RED}fatal: ${e.message}$RESET\r\n")
              128
            },
          )
        }
      }

      "checkout" -> {
        val createFlag = args.contains("-b")
        val branch = args.drop(1).lastOrNull { !it.startsWith("-") }
        if (branch == null) {
          out("${RED}error: missing branch name$RESET\r\n")
          return 1
        }
        if (createFlag) {
          repo.createBranch(branch, checkoutAfter = true).fold(
            onSuccess = {
              out("Switched to a new branch '$branch'\r\n")
              onMutated()
              0
            },
            onFailure = { e ->
              out("${RED}fatal: ${e.message}$RESET\r\n")
              128
            },
          )
        } else {
          repo.checkout(branch).fold(
            onSuccess = {
              out("Switched to branch '$branch'\r\n")
              onMutated()
              0
            },
            onFailure = { e ->
              out("${RED}fatal: ${e.message}$RESET\r\n")
              128
            },
          )
        }
      }

      else -> {
        out("${RED}git: '$sub' is not a git command. See 'git --help'.$RESET\r\n")
        1
      }
    }
  }

  private fun printHelp(out: (String) -> Unit) {
    out(
      """
      |${BOLD}Quark Pure-Kotlin Git CLI$RESET
      |usage: git <command> [<args>]
      |
      |Supported commands:
      |  init [branch]       Create an empty Git repository
      |  status              Show the working tree status
      |  add <path|.>        Add file contents to the index
      |  reset [path]        Unstage changes from the index
      |  commit -m "msg"     Record changes to the repository
      |  log [-n N]          Show commit logs
      |  diff [--staged]     Show changes between commits, index & working tree
      |  branch [name]       List or create branches
      |  checkout [-b] name  Switch or create and switch branches
      |
      """.trimMargin().replace("\n", "\r\n")
    )
  }
}
