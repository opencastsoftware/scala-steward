/*
 * Copyright 2018-2020 Scala Steward contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.scalasteward.core.git

import better.files.File
import cats.Monad
import cats.effect.Bracket
import cats.syntax.all._
import org.http4s.Uri
import org.scalasteward.core.application.Config
import org.scalasteward.core.io.{FileAlg, ProcessAlg, WorkspaceAlg}
import org.scalasteward.core.util.{BracketThrowable, Nel}
import org.scalasteward.core.vcs.data.Repo

trait GitAlg[F[_]] {
  def branchAuthors(repo: Repo, branch: Branch, base: Branch): F[List[String]]

  def checkoutBranch(repo: Repo, branch: Branch): F[Unit]

  def clone(repo: Repo, url: Uri): F[Unit]

  def cloneExists(repo: Repo): F[Boolean]

  def commitAll(repo: Repo, message: String): F[Commit]

  def containsChanges(repo: Repo): F[Boolean]

  def createBranch(repo: Repo, branch: Branch): F[Unit]

  def currentBranch(repo: Repo): F[Branch]

  def findFilesContaining(repo: Repo, string: String): F[List[String]]

  /** Returns `true` if merging `branch` into `base` results in merge conflicts. */
  def hasConflicts(repo: Repo, branch: Branch, base: Branch): F[Boolean]

  def isMerged(repo: Repo, branch: Branch, base: Branch): F[Boolean]

  def latestSha1(repo: Repo, branch: Branch): F[Sha1]

  /** Merges `branch` into the current branch using `theirs` as merge strategy option. */
  def mergeTheirs(repo: Repo, branch: Branch): F[Option[Commit]]

  def push(repo: Repo, branch: Branch): F[Unit]

  def removeClone(repo: Repo): F[Unit]

  def setAuthor(repo: Repo, author: Author): F[Unit]

  def syncFork(repo: Repo, upstreamUrl: Uri, defaultBranch: Branch): F[Unit]

  final def commitAllIfDirty(repo: Repo, message: String)(implicit F: Monad[F]): F[Option[Commit]] =
    containsChanges(repo).ifM(commitAll(repo, message).map(Some.apply), F.pure(None))

  final def returnToCurrentBranch[A, E](repo: Repo)(fa: F[A])(implicit F: Bracket[F, E]): F[A] =
    F.bracket(currentBranch(repo))(_ => fa)(checkoutBranch(repo, _))
}

object GitAlg {

  def create[F[_]](implicit
      config: Config,
      fileAlg: FileAlg[F],
      processAlg: ProcessAlg[F],
      workspaceAlg: WorkspaceAlg[F],
      F: BracketThrowable[F]
  ): GitAlg[F] =
    new GitAlg[F] {
      private val sign: String =
        if (config.signCommits) "--gpg-sign" else "--no-gpg-sign"

      override def branchAuthors(repo: Repo, branch: Branch, base: Branch): F[List[String]] =
        workspaceAlg.repoDir(repo).flatMap { repoDir =>
          exec(Nel.of("log", "--pretty=format:'%an'", dotdot(base, branch)), repoDir)
        }

      override def checkoutBranch(repo: Repo, branch: Branch): F[Unit] =
        for {
          repoDir <- workspaceAlg.repoDir(repo)
          _ <- exec(Nel.of("checkout", branch.name), repoDir)
        } yield ()

      override def clone(repo: Repo, url: Uri): F[Unit] =
        for {
          rootDir <- workspaceAlg.rootDir
          repoDir <- workspaceAlg.repoDir(repo)
          _ <- exec(Nel.of("clone", "--recursive", url.toString, repoDir.pathAsString), rootDir)
        } yield ()

      override def cloneExists(repo: Repo): F[Boolean] =
        for {
          repoDir <- workspaceAlg.repoDir(repo)
          dotGitExists <- fileAlg.isDirectory(repoDir / ".git")
        } yield dotGitExists

      override def commitAll(repo: Repo, message: String): F[Commit] =
        for {
          repoDir <- workspaceAlg.repoDir(repo)
          _ <- exec(Nel.of("commit", "--all", sign, "-m", message), repoDir)
        } yield Commit()

      override def containsChanges(repo: Repo): F[Boolean] =
        workspaceAlg.repoDir(repo).flatMap { repoDir =>
          val args = Nel.of("status", "--porcelain", "--untracked-files=no", "--ignore-submodules")
          exec(args, repoDir).map(_.nonEmpty)
        }

      override def createBranch(repo: Repo, branch: Branch): F[Unit] =
        for {
          repoDir <- workspaceAlg.repoDir(repo)
          _ <- exec(Nel.of("checkout", "-b", branch.name), repoDir)
        } yield ()

      override def currentBranch(repo: Repo): F[Branch] =
        for {
          repoDir <- workspaceAlg.repoDir(repo)
          lines <- exec(Nel.of("rev-parse", "--abbrev-ref", Branch.head.name), repoDir)
        } yield Branch(lines.mkString.trim)

      override def findFilesContaining(repo: Repo, string: String): F[List[String]] =
        for {
          repoDir <- workspaceAlg.repoDir(repo)
          args = Nel.of("grep", "-I", "--fixed-strings", "--files-with-matches", string)
          lines <- exec(args, repoDir).handleError(_ => List.empty[String])
        } yield lines.filter(_.nonEmpty)

      override def hasConflicts(repo: Repo, branch: Branch, base: Branch): F[Boolean] =
        workspaceAlg.repoDir(repo).flatMap { repoDir =>
          val tryMerge = exec(Nel.of("merge", "--no-commit", "--no-ff", branch.name), repoDir)
          val abortMerge = exec(Nel.of("merge", "--abort"), repoDir).void

          returnToCurrentBranch(repo) {
            checkoutBranch(repo, base) >> F.guarantee(tryMerge)(abortMerge).attempt.map(_.isLeft)
          }
        }

      override def isMerged(repo: Repo, branch: Branch, base: Branch): F[Boolean] =
        workspaceAlg.repoDir(repo).flatMap { repoDir =>
          exec(Nel.of("log", "--pretty=format:'%h'", dotdot(base, branch)), repoDir).map(_.isEmpty)
        }

      override def latestSha1(repo: Repo, branch: Branch): F[Sha1] =
        for {
          repoDir <- workspaceAlg.repoDir(repo)
          lines <- exec(Nel.of("rev-parse", "--verify", branch.name), repoDir)
          sha1 <- F.fromEither(Sha1.from(lines.mkString("").trim))
        } yield sha1

      override def mergeTheirs(repo: Repo, branch: Branch): F[Option[Commit]] =
        for {
          before <- latestSha1(repo, Branch.head)
          repoDir <- workspaceAlg.repoDir(repo)
          _ <- exec(Nel.of("merge", "--strategy-option=theirs", sign, branch.name), repoDir)
            .handleErrorWith { throwable =>
              // Resolve CONFLICT (modify/delete) by deleting unmerged files:
              for {
                unmergedFiles <- exec(Nel.of("diff", "--name-only", "--diff-filter=U"), repoDir)
                _ <- Nel.fromList(unmergedFiles.filter(_.nonEmpty)) match {
                  case Some(files) => files.traverse(file => exec(Nel.of("rm", file), repoDir))
                  case None        => F.raiseError(throwable)
                }
                _ <- exec(Nel.of("commit", "--all", "--no-edit", sign), repoDir)
              } yield List.empty
            }
          after <- latestSha1(repo, Branch.head)
        } yield Option.when(before =!= after)(Commit())

      override def push(repo: Repo, branch: Branch): F[Unit] =
        for {
          repoDir <- workspaceAlg.repoDir(repo)
          _ <- exec(Nel.of("push", "--force", "--set-upstream", "origin", branch.name), repoDir)
        } yield ()

      override def removeClone(repo: Repo): F[Unit] =
        workspaceAlg.repoDir(repo).flatMap(fileAlg.deleteForce)

      override def setAuthor(repo: Repo, author: Author): F[Unit] =
        for {
          repoDir <- workspaceAlg.repoDir(repo)
          _ <- exec(Nel.of("config", "user.email", author.email), repoDir)
          _ <- exec(Nel.of("config", "user.name", author.name), repoDir)
        } yield ()

      override def syncFork(repo: Repo, upstreamUrl: Uri, defaultBranch: Branch): F[Unit] =
        for {
          repoDir <- workspaceAlg.repoDir(repo)
          remote = "upstream"
          branch = defaultBranch.name
          remoteBranch = s"$remote/$branch"
          _ <- exec(Nel.of("remote", "add", remote, upstreamUrl.toString), repoDir)
          _ <- exec(Nel.of("fetch", "--tags", remote, branch), repoDir)
          _ <- exec(Nel.of("checkout", "-B", branch, "--track", remoteBranch), repoDir)
          _ <- exec(Nel.of("merge", remoteBranch), repoDir)
          _ <- push(repo, defaultBranch)
        } yield ()

      private def exec(command: Nel[String], cwd: File): F[List[String]] =
        processAlg.exec("git" :: command, cwd, "GIT_ASKPASS" -> config.gitAskPass.pathAsString)
    }
}
