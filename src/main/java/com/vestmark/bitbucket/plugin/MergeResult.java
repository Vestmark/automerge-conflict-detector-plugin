/*
 * Copyright 2020 Vestmark, Inc.
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

import java.util.List;
import com.atlassian.bitbucket.repository.Branch;
import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.bitbucket.scm.git.command.merge.conflict.GitMergeConflict;

public class MergeResult
{
  private final Branch toBranch;
  private final List<GitMergeConflict> mergeConflicts;
  private final List<String> messages;
  private final List<String> files;

  public MergeResult(Branch toBranch, List<GitMergeConflict> mergeConflicts,
                     List<String> messages, List<String> files)
  {
    this.toBranch = toBranch;
    this.mergeConflicts = mergeConflicts;
    this.messages = messages;
    this.files = files;
  }

  public Branch getToBranch()
  {
    return toBranch;
  }

  public String getToBranchDisplayId() 
  {
    return toBranch.getDisplayId();
  }

  public List<GitMergeConflict> getMergeConflicts()
  {
    return mergeConflicts;
 }

  public List<String> getMessages()
  {
    return messages;
  }

  public List<String> getFiles()
  {
    return files;
  }

  public int getMergeConflictsTotal() {
    return (mergeConflicts==null) ? 0 : mergeConflicts.size();
  }
}

