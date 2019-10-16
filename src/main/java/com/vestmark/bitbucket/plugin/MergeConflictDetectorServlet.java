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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.CollectionUtils;

import com.atlassian.bitbucket.auth.AuthenticationContext;
import com.atlassian.bitbucket.branch.model.BranchClassifier;
import com.atlassian.bitbucket.branch.model.BranchModelService;
import com.atlassian.bitbucket.branch.model.BranchType;
import com.atlassian.bitbucket.pull.PullRequest;
import com.atlassian.bitbucket.pull.PullRequestOrder;
import com.atlassian.bitbucket.pull.PullRequestSearchRequest;
import com.atlassian.bitbucket.pull.PullRequestService;
import com.atlassian.bitbucket.pull.PullRequestState;
import com.atlassian.bitbucket.repository.Branch;
import com.atlassian.bitbucket.repository.RefService;
import com.atlassian.bitbucket.scm.MergeCommandParameters;
import com.atlassian.bitbucket.scm.MergeException;
import com.atlassian.bitbucket.scm.git.command.GitExtendedCommandFactory;
import com.atlassian.bitbucket.scm.git.command.merge.GitMergeException;
import com.atlassian.bitbucket.scm.git.command.merge.conflict.GitMergeConflict;
import com.atlassian.bitbucket.util.Page;
import com.atlassian.bitbucket.util.PageRequest;
import com.atlassian.bitbucket.util.PageRequestImpl;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.soy.renderer.SoyException;
import com.atlassian.soy.renderer.SoyTemplateRenderer;
import com.google.common.collect.ImmutableMap;

public class MergeConflictDetectorServlet
    extends HttpServlet
{

  private static final long serialVersionUID = 1L;
  private static final int PR_PAGE_SIZE = 100;
  private final AuthenticationContext authenticationContext;
  private final GitExtendedCommandFactory extendedCmdFactory;
  private final PullRequestService pullRequestService;
  private final RefService refService;
  private final BranchModelService modelService;
  private final SoyTemplateRenderer soyTemplateRenderer;
  private final VersionComparator<Branch> branchComparator;

  @Autowired
  public MergeConflictDetectorServlet(
      @ComponentImport GitExtendedCommandFactory extendedCmdFactory,
      @ComponentImport AuthenticationContext authenticationContext,
      @ComponentImport SoyTemplateRenderer soyTemplateRenderer,
      @ComponentImport RefService refService,
      @ComponentImport BranchModelService modelService,
      @ComponentImport PullRequestService pullRequestService)
  {
    this.authenticationContext = authenticationContext;
    this.extendedCmdFactory = extendedCmdFactory;
    this.pullRequestService = pullRequestService;
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
        pullRequestService.getById(repoId, pullRequestId),
        hostUrl);
    BranchClassifier bc = modelService.getModel(mcd.getToRepo()).getClassifier();
    BranchType toBranchType = bc.getType(mcd.getToBranch());
    // If the target is not master and is a release branch, find target branch and upstream releases (if any).
    if (!mcd.getToBranchId().equals("refs/heads/master") && toBranchType != null
        && toBranchType.getId().equals("RELEASE")) {
      Page<Branch> branches = bc
          .getBranchesByType(toBranchType, new PageRequestImpl(0, PageRequestImpl.MAX_PAGE_LIMIT / 2));
      branches.stream()
          .filter(b -> mcd.isRelated(b))
          .filter(b -> mcd.isUpstreamBranch(b))
          .sorted(branchComparator)
          .forEachOrdered(b -> dryRunMerge(mcd, b));
    }
    dryRunMerge(mcd, refService.getDefaultBranch(mcd.getToRepo())); // Always merge to default.
    checkForAutoMergeFailure(mcd);
    render(response, ImmutableMap.<String, Object> of("mcd", mcd)); // Render Soy template.
  }

  private void checkForAutoMergeFailure(MergeConflictDetector mcd)
  {
    PullRequestSearchRequest prsReq = new PullRequestSearchRequest.Builder().state(PullRequestState.OPEN)
        .order(PullRequestOrder.NEWEST)
        .fromRepositoryId(mcd.getFromRepo().getId())
        .toRepositoryId(mcd.getToRepo().getId())
        .build();
    long totalNPRs = pullRequestService.count(prsReq);
    int maxNPRs = PR_PAGE_SIZE;
    if (totalNPRs < maxNPRs) {
      maxNPRs = (int) totalNPRs;
    }
    int begIdx = 0;
    while (begIdx < totalNPRs) {
      PageRequest pgReq = new PageRequestImpl(begIdx, maxNPRs).buildRestrictedPageRequest(maxNPRs);
      Page<PullRequest> pPRs = pullRequestService.search(prsReq, pgReq);
      boolean amfPRExists = pPRs.stream()
          .anyMatch(r -> r.getTitle().contains("Automatic merge failure") && CollectionUtils.isEmpty(r.getReviewers()));
      if (amfPRExists) {
        mcd.addResult(
            refService.getDefaultBranch(mcd.getToRepo()),
            Collections.emptyList(),
            Arrays.asList("Please check for automatic merge failure!"),
            Collections.emptyList());
        return;
      }
      begIdx += maxNPRs;
    }
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
        // Non Merge Exception
        message.add(e.getMessage());
      }
    }
    mcd.addResult(toBranch, mergeConflicts, message, files);
  }
}
