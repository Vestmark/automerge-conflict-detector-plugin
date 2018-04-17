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
import java.util.List;
import java.util.LinkedList;
import java.util.Map;
import java.lang.reflect.Method;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;

import com.atlassian.bitbucket.auth.AuthenticationContext;
import com.atlassian.bitbucket.repository.Branch;
import com.atlassian.bitbucket.repository.MinimalRef;
import com.atlassian.bitbucket.repository.RefOrder;
import com.atlassian.bitbucket.repository.RefService;
import com.atlassian.bitbucket.repository.RepositoryBranchesRequest;
import com.atlassian.bitbucket.branch.model.BranchModel;
import com.atlassian.bitbucket.branch.model.BranchClassifier;
import com.atlassian.bitbucket.branch.model.BranchModelService;
import com.atlassian.bitbucket.branch.model.BranchType;
import com.atlassian.bitbucket.pull.PullRequestSupplier;
import com.atlassian.bitbucket.scm.MergeCommandParameters;
import com.atlassian.bitbucket.scm.MergeException;
import com.atlassian.bitbucket.scm.git.command.GitExtendedCommandFactory;
import com.atlassian.bitbucket.scm.git.command.merge.GitMergeException;
import com.atlassian.bitbucket.scm.git.command.merge.conflict.GitMergeConflict;
import com.atlassian.bitbucket.util.Page;
import com.atlassian.bitbucket.util.PageRequestImpl;
import com.atlassian.bitbucket.util.PageRequest;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.soy.renderer.SoyException;
import com.atlassian.soy.renderer.SoyTemplateRenderer;
import com.google.common.collect.ImmutableMap;
import com.vestmark.bitbucket.plugin.VersionComparator;

public class MergeConflictDetectorServlet
    extends HttpServlet
{
  private final AuthenticationContext authenticationContext;
  private final GitExtendedCommandFactory extendedCmdFactory;
  private final PullRequestSupplier pullRequestSupplier;
  private final RefService refService;
  private final BranchModelService modelService;
  private final SoyTemplateRenderer soyTemplateRenderer;
  private final VersionComparator<Branch> branchComparator;

  @Autowired
  public MergeConflictDetectorServlet(@ComponentImport PullRequestSupplier pullRequestSupplier, 
                                      @ComponentImport GitExtendedCommandFactory extendedCmdFactory, 
                                      @ComponentImport AuthenticationContext authenticationContext, 
                                      @ComponentImport SoyTemplateRenderer soyTemplateRenderer, 
                                      @ComponentImport RefService refService,
                                      @ComponentImport BranchModelService modelService)
  {
    this.authenticationContext = authenticationContext;
    this.extendedCmdFactory = extendedCmdFactory;
    this.pullRequestSupplier = pullRequestSupplier;
    this.refService = refService;
    this.modelService = modelService;
    this.soyTemplateRenderer = soyTemplateRenderer;
    branchComparator = new VersionComparator<Branch>(Branch::getDisplayId);
  }
  
  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response) 
      throws IOException, ServletException
  {
    // URL path includes numeric repository and pull request IDs to uniquely identify pull request
    String hostUrl = "http://" + request.getServerName() + ":" + request.getServerPort();
    String[] urlPathInfoComponents = request.getPathInfo().split("/");
    int repoId = Integer.parseInt(urlPathInfoComponents[1]);
    long pullRequestId = Long.parseLong(urlPathInfoComponents[2]);
    MergeConflictDetector mcd = new MergeConflictDetector(authenticationContext.getCurrentUser(), 
        pullRequestSupplier.getById(repoId, pullRequestId), hostUrl);
    BranchClassifier bc = modelService.getModel(mcd.getToRepo()).getClassifier();
    BranchType toBranchType = bc.getType(mcd.getToBranch());
    // If the target is not master and is a release branch, find target branch and upstream releases (if any).
    if (toBranchType.getId().equals("RELEASE") && !mcd.getToBranchId().equals("refs/heads/master")) {
      Page<Branch> branches = bc.getBranchesByType(toBranchType, new PageRequestImpl(0,PageRequestImpl.MAX_PAGE_LIMIT/2));
      branches.stream()
              .filter(b -> mcd.isRelated(b))
              .filter(b -> mcd.isUpstreamBranch(b))
              .sorted(branchComparator)
              .forEachOrdered(b -> dryRunMerge(mcd, b));
    }
    dryRunMerge(mcd, refService.getDefaultBranch(mcd.getToRepo())); // Always merge to default.
    render(response, ImmutableMap.<String, Object>of("mcd", mcd));  // Render Soy template.
  }

  protected void render(HttpServletResponse response, Map<String, Object> data) 
      throws IOException, ServletException
  {
    response.setContentType("text/html;charset=UTF-8");
    try {
      soyTemplateRenderer.render(response.getWriter(), 
                                 "com.vestmark.bitbucket.automergeconflictdetector:mcd.soy", 
                                 "plugin.mcd.conflictsTab", 
                                 data);
    } catch (SoyException e) {
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
    MergeCommandParameters params = new MergeCommandParameters
        .Builder()
        .dryRun(true)
        .author(mcd.getUser())
        .fromBranch(mcd.getFromBranchId())
        .toBranch(toBranch.getId())
        .message("MergeConflictDetector dry run merge check.")
        .build();
    try {
      Branch result = extendedCmdFactory.merge(mcd.getToRepo(), params).call();
      // A result from the merge indicates an unsuccesful dry run!
      if (result != null) {
        message.add("Merge committed! Commit ID: " + result.getLatestCommit());
      }
    } catch (MergeException e) {
      files = new LinkedList<String>();
      mergeConflicts = new LinkedList<GitMergeConflict>();
      // When a merge cannot be completed automatically, a CommandFailedException is being thrown and caught in this MergeException block by mistake.
      // The (GitMergeException) cast below causes the plugin to crash because of it.
      // Adding a CommandFailedException catch block did not work.
      // Checking the type of the Exception obj prior to the cast using instanceof did not work.
      // Encasing the cast inside its own try/catch was the only way I could find to keep the plugin from crashing.
      try {
        for (GitMergeConflict mergeConflict : ((GitMergeException)e.getCause()).getConflicts()) {
          files.add(mergeConflict.getMessage().replaceFirst("Merge conflict in ", ""));
          message.add("Source change: " + mergeConflict.getOurChange() + " Target change: " 
                                        + mergeConflict.getTheirChange());
          mergeConflicts.add(mergeConflict);
        }
      } catch (Exception f) {
        message.add(e.getMessage());
      }
    }

    mcd.addResult(toBranch, mergeConflicts, message, files);
  }
}

