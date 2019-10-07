/*
 * Copyright 2017 Vestmark, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except 
 * in compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express 
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.vestmark.bitbucket.plugin;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;

import com.atlassian.bitbucket.ServerException;
import com.atlassian.bitbucket.auth.AuthenticationContext;
import com.atlassian.bitbucket.branch.model.BranchClassifier;
import com.atlassian.bitbucket.branch.model.BranchModelService;
import com.atlassian.bitbucket.branch.model.BranchType;
import com.atlassian.bitbucket.comment.CommentThread;
import com.atlassian.bitbucket.compare.CompareRef;
import com.atlassian.bitbucket.compare.CompareRequest;
import com.atlassian.bitbucket.content.ConflictMarker;
import com.atlassian.bitbucket.content.DiffContentCallback;
import com.atlassian.bitbucket.content.DiffContext;
import com.atlassian.bitbucket.content.DiffSegmentType;
import com.atlassian.bitbucket.content.DiffSummary;
import com.atlassian.bitbucket.content.Path;
import com.atlassian.bitbucket.i18n.KeyedMessage;
import com.atlassian.bitbucket.pull.PullRequestSupplier;
import com.atlassian.bitbucket.repository.Branch;
import com.atlassian.bitbucket.repository.RefService;
import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.bitbucket.scm.MergeCommandParameters;
import com.atlassian.bitbucket.scm.MergeException;
import com.atlassian.bitbucket.scm.compare.CompareDiffCommandParameters;
import com.atlassian.bitbucket.scm.git.command.GitCommand;
import com.atlassian.bitbucket.scm.git.command.GitCompareCommandFactory;
import com.atlassian.bitbucket.scm.git.command.GitExtendedCommandFactory;
import com.atlassian.bitbucket.scm.git.command.merge.GitMergeException;
import com.atlassian.bitbucket.scm.git.command.merge.conflict.GitMergeConflict;
import com.atlassian.bitbucket.scm.git.command.merge.conflict.UnknownGitMergeConflict;
import com.atlassian.bitbucket.util.Page;
import com.atlassian.bitbucket.util.PageRequestImpl;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.soy.renderer.SoyException;
import com.atlassian.soy.renderer.SoyTemplateRenderer;
import com.google.common.collect.ImmutableMap;

public class MergeConflictDetectorServlet
    extends HttpServlet
{

  private static final long serialVersionUID = 1L;
  private static final String POSSIBLE_AUTO_MERGE_CONFLICT = "Possible auto-merge conflict";
  private static final String POSS_AUTO_MERGE_CONFLICT_KEY = "PossAutoMergeConflict";
  private static final int MSG_LENGTH_LIMIT = POSSIBLE_AUTO_MERGE_CONFLICT.length() + 3;
  private final AuthenticationContext authenticationContext;
  private final GitExtendedCommandFactory extendedCmdFactory;
  private final GitCompareCommandFactory compareCommandFactory;
  private final PullRequestSupplier pullRequestSupplier;
  private final RefService refService;
  private final BranchModelService modelService;
  private final SoyTemplateRenderer soyTemplateRenderer;
  private final VersionComparator<Branch> branchComparator;

  @Autowired
  public MergeConflictDetectorServlet(
      @ComponentImport PullRequestSupplier pullRequestSupplier,
      @ComponentImport GitExtendedCommandFactory extendedCmdFactory,
      @ComponentImport GitCompareCommandFactory compareCommandFactory,
      @ComponentImport AuthenticationContext authenticationContext,
      @ComponentImport SoyTemplateRenderer soyTemplateRenderer,
      @ComponentImport RefService refService,
      @ComponentImport BranchModelService modelService)
  {
    this.authenticationContext = authenticationContext;
    this.extendedCmdFactory = extendedCmdFactory;
    this.compareCommandFactory = compareCommandFactory;
    this.pullRequestSupplier = pullRequestSupplier;
    this.refService = refService;
    this.modelService = modelService;
    this.soyTemplateRenderer = soyTemplateRenderer;
    branchComparator = new VersionComparator<Branch>(Branch::getDisplayId);
  }

  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
  {
    // URL path includes numeric repository and pull request IDs to uniquely identify pull request
    String hostUrl = "http://" + request.getServerName() + ":" + request.getServerPort();
    String[] urlPathInfoComponents = request.getPathInfo().split("/");
    int repoId = Integer.parseInt(urlPathInfoComponents[1]);
    long pullRequestId = Long.parseLong(urlPathInfoComponents[2]);
    MergeConflictDetector mcd = new MergeConflictDetector(
        authenticationContext.getCurrentUser(),
        pullRequestSupplier.getById(repoId, pullRequestId),
        hostUrl);
    BranchClassifier bc = modelService.getModel(mcd.getToRepo()).getClassifier();
    BranchType toBranchType = bc.getType(mcd.getToBranch());
    Branch defBranch = refService.getDefaultBranch(mcd.getToRepo());
    // If the target is not master and is a release branch, find target branch and upstream releases (if any).
    if (!mcd.getToBranchId().equals("refs/heads/master") && toBranchType != null
        && toBranchType.getId().equals("RELEASE")) {
      Page<Branch> branches = bc
          .getBranchesByType(toBranchType, new PageRequestImpl(0, PageRequestImpl.MAX_PAGE_LIMIT / 2));
      branches.stream()
          .filter(b -> mcd.isRelated(b))
          .filter(b -> mcd.isUpstreamBranch(b))
          .sorted(branchComparator)
          .forEachOrdered(b -> dryRunMerge(mcd, b, defBranch.getId()));
    }
    dryRunMerge(mcd, defBranch); // Always merge to default.
    render(response, ImmutableMap.<String, Object> of("mcd", mcd)); // Render Soy template.
  }

  protected void render(HttpServletResponse response, Map<String, Object> data) throws IOException, ServletException
  {
    response.setContentType("text/html;charset=UTF-8");
    try {
      soyTemplateRenderer.render(
          response.getWriter(),
          "com.vestmark.bitbucket.automergeconflictdetector:mcd.soy",
          "plugin.mcd.conflictsTab",
          data);
    }
    catch (SoyException e) {
      Throwable cause = e.getCause();
      if (cause instanceof IOException) {
        throw (IOException) cause;
      }

      throw new ServletException(e);
    }
  }

  public void dryRunMerge(MergeConflictDetector mcd, Branch toBranch)
  {
    dryRunMerge(mcd, toBranch, toBranch.getId());
  }

  public void dryRunMerge(MergeConflictDetector mcd, Branch toBranch, String defBranchId)
  {
    List<String> files = null;
    List<GitMergeConflict> mergeConflicts = null;
    List<String> message = new LinkedList<String>();
    MergeCommandParameters params = new MergeCommandParameters.Builder().dryRun(true)
        .author(mcd.getUser())
        .fromBranch(mcd.getFromBranchId())
        .toBranch(toBranch.getId())
        .message("MergeConflictDetector dry run merge check.")
        .build();
    try {
      Branch result = extendedCmdFactory.merge(mcd.getToRepo(), params).call();
      // A result from the merge indicates an unsuccessful dry run!
      if (result != null) {
        message.add("Merge committed! Commit ID: " + result.getLatestCommit());
      }
      doAheadCheck(toBranch.getId(), mcd.getToRepo(), defBranchId);
    }
    catch (Exception e) {
      if (e instanceof MergeException) {
        files = new LinkedList<String>();
        mergeConflicts = new LinkedList<GitMergeConflict>();
        // When a merge cannot be completed automatically, a CommandFailedException is being thrown and caught in this
        // MergeException block by mistake.
        // The (GitMergeException) cast below causes the plugin to crash because of it.
        // Adding a CommandFailedException catch block did not work.
        // Checking the type of the Exception obj prior to the cast using instanceof did not work.
        // Encasing the cast inside its own try/catch was the only way I could find to keep the plugin from crashing.
        try {
          for (GitMergeConflict mergeConflict : ((GitMergeException) e.getCause()).getConflicts()) {
            files.add(mergeConflict.getMessage().replaceFirst("Merge conflict in ", ""));
            message.add(
                "Source change: " + mergeConflict.getOurChange() + " Target change: " + mergeConflict.getTheirChange());
            mergeConflicts.add(mergeConflict);
          }
        }
        catch (Exception f) {
          message.add(e.getMessage());
        }
      }
      else {
        message.add("Non-Merge Exception: " + e.getMessage());
      }
    }
    mcd.addResult(toBranch, mergeConflicts, message, files);
  }

  private void doAheadCheck(String fromBranchId, Repository repository, String toId) throws MergeException
  {
    try {
      CompareRef fromRef = new CompareRef(fromBranchId, repository);
      CompareRef toRef = new CompareRef(toId, repository);
      CompareRequest compareReq = new CompareRequest.Builder().fromRef(fromRef).toRef(toRef).build();
      CompareDiffCommandParameters diffParams = new CompareDiffCommandParameters.Builder().contextLines(10)
          .maxLineLength(100)
          .maxLines(15)
          .build();
      final StringBuilder mcMsg = new StringBuilder(POSSIBLE_AUTO_MERGE_CONFLICT).append("s: ");
      GitCommand<Void> gitCommand = compareCommandFactory.diff(compareReq, diffParams, new DiffContentCallback() {

        @Override
        public void onEnd(DiffSummary summary)
        {
        }

        @Override
        public void offerThreads(Stream<CommentThread> arg0) throws IOException
        {
        }

        @Override
        public void onBinary(Path arg0, Path arg1) throws IOException
        {
        }

        @Override
        public void onDiffEnd(boolean isTruncated) throws IOException
        {
          if (isTruncated) {
            mcMsg.append("...");
          }
        }

        @Override
        public void onDiffStart(Path pth1, Path pth2) throws IOException
        {
          if (mcMsg.length() > MSG_LENGTH_LIMIT) {
            mcMsg.append(",");
          }
          if (pth1 != null) {
            mcMsg.append(pth1.toString());
          }
          else if (pth2 != null) {
            mcMsg.append(pth2.toString());
          }
        }

        @Override
        public void onHunkEnd(boolean arg0) throws IOException
        {
        }

        @Override
        public void onSegmentEnd(boolean arg0) throws IOException
        {
        }

        @Override
        public void onSegmentLine(String arg0, ConflictMarker arg1, boolean arg2) throws IOException
        {
        }

        @Override
        public void onSegmentStart(DiffSegmentType arg0) throws IOException
        {
        }

        @Override
        public void onStart(DiffContext arg0) throws IOException
        {
        }
      });
      gitCommand.call();
      if (mcMsg.length() > MSG_LENGTH_LIMIT) {
        KeyedMessage km = new KeyedMessage(POSS_AUTO_MERGE_CONFLICT_KEY, mcMsg.toString(), mcMsg.toString());
        throwGitMergeException(
            km,
            POSSIBLE_AUTO_MERGE_CONFLICT,
            mcMsg.toString(),
            POSS_AUTO_MERGE_CONFLICT_KEY,
            repository,
            fromBranchId,
            toId);
      }
    }
    catch (ServerException se) {
      throwGitMergeException(
          se.getKeyedMessage(),
          "Error parsing " + POSSIBLE_AUTO_MERGE_CONFLICT.toLowerCase(),
          "Error parsing " + POSSIBLE_AUTO_MERGE_CONFLICT.toLowerCase() + ": " + se.getLocalizedMessage(),
          "DiffParseFail",
          repository,
          fromBranchId,
          toId);
    }
  }

  private void throwGitMergeException(
      KeyedMessage km,
      String rsn,
      String msg,
      String scmId,
      Repository repo,
      String fromId,
      String toId)
    throws MergeException
  {
    GitMergeConflict gmc = new UnknownGitMergeConflict(rsn, msg);
    GitMergeException gme = new GitMergeException(km, Arrays.asList(gmc));
    throw new MergeException(km, gme, scmId, repo, fromId, toId, true);
  }
}
