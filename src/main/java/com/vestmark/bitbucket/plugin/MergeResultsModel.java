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

import javax.xml.bind.annotation.*;
import java.util.List;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;

@XmlRootElement(name = "mergeresultsmodel")
@XmlAccessorType(XmlAccessType.PROPERTY)
public class MergeResultsModel {

    private String toBranchDisplayId;
    private String toBranchId;
    private int mergeConflicts;
    private List<String> mergeMessages;
    private List<String> mergeFiles;

    public MergeResultsModel(String toBranchDisplayId,
                             String toBranchId,
                             int mergeConflicts,
                             List<String> mergeMessages,
                             List<String> mergeFiles) {
      this.toBranchDisplayId = toBranchDisplayId;
      this.toBranchId = toBranchId;
      this.mergeConflicts = mergeConflicts;
      this.mergeMessages = mergeMessages;
      this.mergeFiles = mergeFiles;
    }

    public String getToBranchDisplayId() {
      return toBranchDisplayId;
    }

    public String getToBranchId() {
      return toBranchId;
    }

    public int getMergeConflicts() {
      return mergeConflicts;
    }

    public List<String> getMergeMessages() {
      return (mergeMessages == null) ? Collections.singletonList("None") : mergeMessages;
    }

    public List<String> getMergeFiles() {
      return (mergeFiles == null) ? Collections.singletonList("None") : mergeFiles;
    }

}
