package org.sonar.plugins.stash;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.PostJob;
import org.sonar.api.batch.SensorContext;
import org.sonar.api.issue.ProjectIssues;
import org.sonar.api.resources.Project;
import org.sonar.plugins.stash.client.SonarQubeClient;
import org.sonar.plugins.stash.client.StashClient;
import org.sonar.plugins.stash.client.StashCredentials;
import org.sonar.plugins.stash.exceptions.StashConfigurationException;
import org.sonar.plugins.stash.issue.CoverageIssuesReport;
import org.sonar.plugins.stash.issue.SonarQubeIssuesReport;
import org.sonar.plugins.stash.issue.StashDiffReport;
import org.sonar.plugins.stash.issue.StashUser;

public class StashIssueReportingPostJob implements PostJob {

  private static final Logger LOGGER = LoggerFactory.getLogger(StashIssueReportingPostJob.class);
  private static final String STACK_TRACE = "Exception stack trace";
  
  private final ProjectIssues projectIssues;
  private final StashPluginConfiguration config;
  private final InputFileCache inputFileCache;
  private final StashRequestFacade stashRequestFacade;
  private final InputFileCacheSensor inputFileCacheSensor;
    
  public StashIssueReportingPostJob(StashPluginConfiguration stashPluginConfiguration, ProjectIssues projectIssues,
      InputFileCache inputFileCache, StashRequestFacade stashRequestFacade, InputFileCacheSensor inputFileCacheSensor) {
    this.projectIssues = projectIssues;
    this.config = stashPluginConfiguration;
    this.inputFileCache = inputFileCache;
    this.stashRequestFacade = stashRequestFacade;
    this.inputFileCacheSensor = inputFileCacheSensor;
  }

  @Override
  public void executeOn(Project project, SensorContext context) {

    try {
      boolean notifyStash = config.hasToNotifyStash();
      if (notifyStash) {

        // Stash MANDATORY options
        String stashURL  = stashRequestFacade.getStashURL();
        int stashTimeout = config.getStashTimeout();
          
        StashCredentials stashCredentials = stashRequestFacade.getCredentials();

        try (StashClient stashClient = new StashClient(stashURL, stashCredentials, stashTimeout, config.getSonarQubeVersion())) {

          // Down the rabbit hole...
          updateStashWithSonarInfo(stashClient, stashCredentials, project, context);
        }
      }
    } catch (StashConfigurationException e) {
      LOGGER.error("Unable to push SonarQube report to Stash: {}", e.getMessage());
      LOGGER.debug(STACK_TRACE, e);
    }
  }


  /*
  * Second part of the code necessary for the executeOn() -- squid:S134
  */
  private void updateStashWithSonarInfo( StashClient stashClient, StashCredentials stashCredentials,
                                                 Project project, SensorContext context) {

    try {
      int issueThreshold = stashRequestFacade.getIssueThreshold();
      String sonarQubeURL = config.getSonarQubeURL();

      // Stash MANDATORY options
      String stashURL = stashRequestFacade.getStashURL();
      String stashProject = stashRequestFacade.getStashProject();
      String repository = stashRequestFacade.getStashRepository();
      String stashPullRequestId = stashRequestFacade.getStashPullRequestId();

      // SonarQUbe objects
      SonarQubeClient sonarqubeClient   = new SonarQubeClient(sonarQubeURL);
      SonarQubeIssuesReport issueReport = stashRequestFacade.extractIssueReport(projectIssues, inputFileCache);


      StashUser stashUser = stashRequestFacade.getSonarQubeReviewer(stashCredentials.getLogin(), stashClient);

      if (stashUser == null) {
        throw new StashMissingElementException("No SonarQube reviewer identified to publish to Stash the SQ analysis");
      }

      // Get all changes exposed from Stash differential view of the pull-request
      StashDiffReport diffReport = stashRequestFacade.getPullRequestDiffReport(stashProject, repository,
                                                                               stashPullRequestId, stashClient);
      if (diffReport == null) {
        throw new StashMissingElementException("No Stash differential report available to process the SQ analysis");
      }

      // if requested, reset all comments linked to the pull-request
      if (config.resetComments()) {
        stashRequestFacade.resetComments(stashProject, repository, stashPullRequestId,
                                           diffReport, stashUser, stashClient);
      }

      boolean canApprovePullrequest = config.canApprovePullRequest();
      if (canApprovePullrequest) {
        stashRequestFacade.addPullRequestReviewer(stashProject, repository, stashPullRequestId,
                                                                   stashCredentials.getLogin(), stashClient);
      }

      // build code coverage if requested
      CoverageIssuesReport coverageReport = new CoverageIssuesReport();

      String codeCoverageSeverity = stashRequestFacade.getCodeCoverageSeverity();

      if (! StringUtils.equals(codeCoverageSeverity, StashPlugin.SEVERITY_NONE)) {
        coverageReport = stashRequestFacade.getCoverageReport(project.getKey(), context,
                                                                   inputFileCacheSensor,
                                                                   codeCoverageSeverity, sonarqubeClient);
      }

      int issueNumber = issueReport.countIssues() + coverageReport.countLoweredIssues();
        
      // if threshold exceeded, do not push issue list to Stash
      if (issueNumber >= issueThreshold) {
        LOGGER.warn("Too many issues detected ({}/{}): Issues cannot be displayed in Diff view",
                                                       issueReport.countIssues(), issueThreshold);
      } else {
        // publish SonarQube issue and code coverage
        stashRequestFacade.postSonarQubeReport(stashProject, repository, stashPullRequestId, sonarQubeURL,
                                                            issueReport, diffReport, stashClient);
        stashRequestFacade.postCoverageReport(stashProject, repository, stashPullRequestId, sonarQubeURL,
                                                        coverageReport, diffReport, stashClient);
      }

      // Compacting all string info into an array to match the method's compact signature (squid:S00107)
      String[] info = {stashProject, repository, stashPullRequestId, sonarQubeURL, stashURL};

      stashRequestFacade.postAnalysisOverview(info, issueThreshold, issueReport, coverageReport, stashClient);

      // if no new issues and coverage is improved,
      // plugin approves the pull-request
      if (canApprovePullrequest && (issueNumber == 0) && (coverageReport.getEvolution() >= 0)){

        stashRequestFacade.approvePullRequest(stashProject, repository, stashPullRequestId,
                                                               stashCredentials.getLogin(), stashClient);

      } else if (canApprovePullrequest && ( (issueNumber != 0) || coverageReport.getEvolution() < 0) ) {

        stashRequestFacade.resetPullRequestApproval(stashProject, repository, stashPullRequestId,
                                                                     stashCredentials.getLogin(), stashClient);
      }

    } catch (StashConfigurationException e) {
      LOGGER.error("Unable to push SonarQube report to Stash: {}", e.getMessage());
      LOGGER.debug(STACK_TRACE, e);

    } catch (StashMissingElementException e) {
      LOGGER.error("Process stopped: {}", e.getMessage());
      LOGGER.debug(STACK_TRACE, e);
    }
  }


  /*
  *  Custom exception to keep nested if statements under control
  */
  private class StashMissingElementException extends Exception {

    public StashMissingElementException(String exc) {
        super(exc);
    }
  }
}
