package com.vestmark.bitbucket.rest;

import javax.xml.bind.annotation.*;
import java.util.List;
import java.util.Arrays;
import java.util.ArrayList;

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
      return (mergeMessages == null) ? (new ArrayList<>(Arrays.asList("None"))) : mergeMessages;
    }

    public List<String> getMergeFiles() {
      return (mergeFiles == null) ? (new ArrayList<>(Arrays.asList("None"))) : mergeFiles;
    }

}
