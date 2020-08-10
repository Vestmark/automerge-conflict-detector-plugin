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

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.CollectionUtils;

import com.atlassian.bitbucket.server.ApplicationPropertiesService;
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
import com.google.common.collect.ImmutableMap;

import com.atlassian.plugins.rest.common.security.AnonymousAllowed;

import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.GET;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.annotation.PostConstruct;

/**
* A resource of message.
*/
@Path("/merge-conflicts")
public class MergeConflictDetectorService {

    private static final long serialVersionUID = 1L;
    private static final String AUTO_MERGE_FAIL = "Automatic merge failure";
    private static String HOST_URL;

    private final ApplicationPropertiesService applicationPropertiesService; 
    private final AuthenticationContext authenticationContext;
    private final GitExtendedCommandFactory extendedCmdFactory;
    private final PullRequestService pullRequestService;
    private final RefService refService;
    private final BranchModelService modelService;
    private final VersionComparator<Branch> branchComparator;

    @Autowired
    public MergeConflictDetectorService(@ComponentImport ApplicationPropertiesService applicationPropertiesService,
                                        @ComponentImport PullRequestService pullRequestService, 
                                        @ComponentImport GitExtendedCommandFactory extendedCmdFactory, 
                                        @ComponentImport AuthenticationContext authenticationContext, 
                                        @ComponentImport RefService refService,
                                        @ComponentImport BranchModelService modelService) 
   {
      this.applicationPropertiesService = applicationPropertiesService;
      this.authenticationContext = authenticationContext;
      this.extendedCmdFactory = extendedCmdFactory;
      this.pullRequestService = pullRequestService;
      this.refService = refService;
      this.modelService = modelService;
      branchComparator = new VersionComparator<Branch>(Branch::getDisplayId);
    }

    @PostConstruct
    private void postConstruct() {
      HOST_URL = applicationPropertiesService.getBaseUrl().toString();
    }

    @GET
    @AnonymousAllowed
    @Produces({MediaType.APPLICATION_JSON})
    @Path("/{repoIdStr}/{pullRequestIdStr}")
    public Response getMergeResults(@PathParam("repoIdStr") String repoIdParam, @PathParam("pullRequestIdStr") String pullRequestIdParam)
    {
      int repoId = Integer.parseInt(repoIdParam);
      long pullRequestId = Long.parseLong(pullRequestIdParam);
      MergeConflictDetector mcd = new MergeConflictDetector(authenticationContext.getCurrentUser(), 
        pullRequestService.getById(repoId, pullRequestId), HOST_URL);
      BranchClassifier bc = modelService.getModel(mcd.getToRepo()).getClassifier();
      BranchType toBranchType = bc.getType(mcd.getToBranch());
      // If the target is not master and is a release branch, find target branch and upstream releases (if any).
      if (!mcd.getToBranchId().equals("refs/heads/master") && toBranchType != null && toBranchType.getId().equals("RELEASE") ) {
        Page<Branch> branches = bc.getBranchesByType(toBranchType, new PageRequestImpl(0,PageRequestImpl.MAX_PAGE_LIMIT/2));
        branches.stream()
              .parallel()
              .filter(b -> mcd.isRelated(b))
              .filter(b -> mcd.isUpstreamBranch(b))
              .sorted(branchComparator)
              .forEach(b -> mcd.addResult(dryRunMerge(mcd, b)));
      }
      mcd.sortMergeResults();
      mcd.addResult(dryRunMerge(mcd, refService.getDefaultBranch(mcd.getToRepo()))); // Always merge to default.
      checkForAutoMergeFailure(mcd);
      return Response.ok(mcd.getMergeResultsModelList()).build();
    }

    private void checkForAutoMergeFailure(MergeConflictDetector mcd)
    {
      try {
        PullRequestSearchRequest prsReq = new PullRequestSearchRequest.Builder().state(PullRequestState.OPEN)
          .order(PullRequestOrder.NEWEST)
          .fromRepositoryId(mcd.getFromRepo().getId())
          .toRepositoryId(mcd.getToRepo().getId())
          .build();
        long totalNPRs = pullRequestService.count(prsReq);
        int maxNPRs = 100;
        if (totalNPRs < maxNPRs) {
          maxNPRs = (int) totalNPRs;
        }
        int begIdx = 0;
        while (begIdx < totalNPRs) {
          PageRequest pgReq = new PageRequestImpl(begIdx, maxNPRs).buildRestrictedPageRequest(maxNPRs);
          Page<PullRequest> pPRs = pullRequestService.search(prsReq, pgReq);
          boolean amfPRExists = pPRs.stream().anyMatch(r -> r.getTitle().equals(AUTO_MERGE_FAIL));
          if (amfPRExists) {
            mcd.addResult(
              refService.getDefaultBranch(mcd.getToRepo()),
              Collections.emptyList(),
              Arrays.asList("Please check for " + AUTO_MERGE_FAIL + "!"),
              Collections.emptyList());
            return;
          }
          begIdx += maxNPRs;
        }
      }
      catch (Exception e) {
        mcd.addResult(refService.getDefaultBranch(mcd.getToRepo()), null, Arrays.asList(e.getMessage()), null);
      }
    }

    private MergeResult dryRunMerge(MergeConflictDetector mcd, Branch toBranch)
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
        // A result from the merge indicates an unsuccessful dry run!
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
      } catch (Exception e) {
        // Non Merge Exception
        message.add(e.getMessage());
      }
      return new MergeResult(toBranch, mergeConflicts, message, files);
    }
}

